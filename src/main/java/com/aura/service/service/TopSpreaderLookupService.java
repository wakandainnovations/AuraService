package com.aura.service.service;

import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.proxy.TtlCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class TopSpreaderLookupService {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AuraMathProxyService proxy;
    private final ObjectMapper objectMapper;
    private final TtlCache<Set<String>> cache = new TtlCache<>(1024);
    private final TtlCache<List<SpreaderProfile>> profileCache = new TtlCache<>(1024);

    // influenceTier/primaryPlatform are not part of AuraMath's top-50-spreaders response contract and
    // are always null in practice; totalViews (AuraMath's total_views) is the only real reach proxy
    // that endpoint provides, and is what ranking should key off instead. profileUrl comes from this
    // endpoint's flat profile_url field (AuraMath's own attachProfileLinks enrichment step) - null when
    // that author hasn't been enriched yet, never fabricated from globalUserId.
    public record SpreaderProfile(String globalUserId, String primaryPlatform, String influenceTier, long totalViews,
                                   String profileUrl) {}

    public TopSpreaderLookupService(AuraMathProxyService proxy, ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    public Set<String> getSpreaders(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> cached = cache.get(keyword);
        if (cached != null) {
            return cached;
        }
        Set<String> fresh = fetch(keyword);
        if (fresh == null) {
            return Collections.emptySet();
        }
        cache.put(keyword, fresh, CACHE_TTL.toNanos());
        return fresh;
    }

    public List<SpreaderProfile> getSpreaderProfiles(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        List<SpreaderProfile> cached = profileCache.get(keyword);
        if (cached != null) {
            return cached;
        }
        List<SpreaderProfile> fresh = fetchProfiles(keyword);
        if (fresh == null) {
            return Collections.emptyList();
        }
        profileCache.put(keyword, fresh, CACHE_TTL.toNanos());
        return fresh;
    }

    void invalidateAll() {
        cache.clear();
        profileCache.clear();
    }

    private Set<String> fetch(String keyword) {
        ResponseEntity<String> response = proxy.forwardGet(
                "/v1/top-spreaders/{keyword}",
                "/api/marketing/top-50-spreaders/" + encodeSegment(keyword),
                null,
                false,
                null
        );
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("top-spreaders lookup failed keyword={} status={}",
                    keyword, response.getStatusCode().value());
            return null;
        }
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return Set.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                log.warn("top-spreaders payload not an array keyword={}", keyword);
                return Set.of();
            }
            Set<String> result = new HashSet<>(root.size());
            for (JsonNode element : root) {
                String author = extractAuthor(element);
                if (author != null && !author.isBlank()) {
                    result.add(author);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse top-spreaders payload keyword={}", keyword, e);
            return null;
        }
    }

    private List<SpreaderProfile> fetchProfiles(String keyword) {
        ResponseEntity<String> response = proxy.forwardGet(
                "/v1/top-spreaders/{keyword}",
                "/api/marketing/top-50-spreaders/" + encodeSegment(keyword),
                null,
                false,
                null
        );
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("top-spreaders profile lookup failed keyword={} status={}",
                    keyword, response.getStatusCode().value());
            return null;
        }
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                log.warn("top-spreaders payload not an array keyword={}", keyword);
                return List.of();
            }
            Map<String, SpreaderProfile> deduped = new LinkedHashMap<>();
            for (JsonNode element : root) {
                String author = extractAuthor(element);
                if (author == null || author.isBlank()) {
                    continue;
                }
                String platform = extractField(element, "primaryPlatform", "platform");
                String tier = extractField(element, "influenceTier", "tier");
                long totalViews = extractNumericField(element, "total_views", "totalViews");
                String profileUrl = AuthorProfileLinkResolver.extractProfileUrl(element);
                deduped.putIfAbsent(author, new SpreaderProfile(author, platform, tier, totalViews, profileUrl));
            }
            return new ArrayList<>(deduped.values());
        } catch (Exception e) {
            log.warn("Failed to parse top-spreaders payload keyword={}", keyword, e);
            return null;
        }
    }

    private static long extractNumericField(JsonNode element, String... fields) {
        if (!element.isObject()) {
            return 0L;
        }
        for (String field : fields) {
            JsonNode v = element.get(field);
            if (v != null && v.isNumber()) {
                return v.asLong();
            }
        }
        return 0L;
    }

    private static String extractField(JsonNode element, String... fields) {
        if (!element.isObject()) {
            return null;
        }
        for (String field : fields) {
            JsonNode v = element.get(field);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private static String extractAuthor(JsonNode element) {
        if (element.isTextual()) {
            return element.asText();
        }
        if (element.isObject()) {
            for (String field : new String[]{"author", "globalUserId", "userId", "username"}) {
                JsonNode v = element.get(field);
                if (v != null && v.isTextual()) {
                    return v.asText();
                }
            }
        }
        return null;
    }

    private static String encodeSegment(String segment) {
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
