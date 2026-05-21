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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
public class TopSpreaderLookupService {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AuraMathProxyService proxy;
    private final ObjectMapper objectMapper;
    private final TtlCache<Set<String>> cache = new TtlCache<>(1024);

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

    void invalidateAll() {
        cache.clear();
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
