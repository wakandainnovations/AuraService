package com.aura.service.service;

import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.proxy.TtlCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class PlaybookLookupServiceImpl implements PlaybookLookupService {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AuraMathProxyService proxy;
    private final ObjectMapper objectMapper;
    private final TtlCache<List<PlaybookPattern>> cache = new TtlCache<>(1024);

    public PlaybookLookupServiceImpl(AuraMathProxyService proxy, ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PlaybookPattern> getPlaybookPatterns(String industry, String language) {
        String cacheKey = industry + "|" + language;
        List<PlaybookPattern> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String upstreamPath = "/api/marketing/playbook?industry=" + encodeQueryValue(industry)
                + "&language=" + encodeQueryValue(language);
        ResponseEntity<String> response = proxy.forwardMarketingGet(
                "/v1/marketing/playbook", upstreamPath, 60);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("playbook lookup failed industry={} language={} status={}", industry, language,
                    response.getStatusCode().value());
            return Collections.emptyList();
        }

        List<PlaybookPattern> parsed = parse(response.getBody(), industry, language);
        cache.put(cacheKey, parsed, CACHE_TTL.toNanos());
        return parsed;
    }

    private List<PlaybookPattern> parse(String body, String industry, String language) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"ok".equals(root.path("status").asText(""))) {
                return Collections.emptyList();
            }
            JsonNode patterns = root.get("patterns");
            if (patterns == null || !patterns.isArray()) {
                return Collections.emptyList();
            }

            List<PlaybookPattern> result = new ArrayList<>();
            for (JsonNode p : patterns) {
                JsonNode sequenceNode = p.get("patternSequence");
                JsonNode supportTopTier = p.get("supportTopTier");
                JsonNode supportBottomTier = p.get("supportBottomTier");
                JsonNode pValue = p.get("pValue");
                JsonNode fdrQValue = p.get("fdrQValue");
                JsonNode nEntities = p.get("nEntities");
                if (sequenceNode == null || !sequenceNode.isArray()
                        || supportTopTier == null || !supportTopTier.isNumber()
                        || supportBottomTier == null || !supportBottomTier.isNumber()
                        || pValue == null || !pValue.isNumber()
                        || fdrQValue == null || !fdrQValue.isNumber()
                        || nEntities == null || !nEntities.isNumber()) {
                    continue;
                }
                List<String> sequence = new ArrayList<>();
                for (JsonNode step : sequenceNode) {
                    if (step.isTextual() && !step.asText().isBlank()) {
                        sequence.add(step.asText());
                    }
                }
                if (sequence.isEmpty()) {
                    continue;
                }
                result.add(new PlaybookPattern(sequence, supportTopTier.asLong(), supportBottomTier.asLong(),
                        pValue.asDouble(), fdrQValue.asDouble(), nEntities.asLong()));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse playbook payload industry={} language={}", industry, language, e);
            return Collections.emptyList();
        }
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
