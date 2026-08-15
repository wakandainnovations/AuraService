package com.aura.service.service;

import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.proxy.TtlCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ViralSeedLookupServiceImpl implements ViralSeedLookupService {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AuraMathProxyService proxy;
    private final ObjectMapper objectMapper;
    private final TtlCache<List<ViralSeed>> cache = new TtlCache<>(1024);

    public ViralSeedLookupServiceImpl(AuraMathProxyService proxy, ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ViralSeed> getViralSeeds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        List<ViralSeed> cached = cache.get(keyword);
        if (cached != null) {
            return cached;
        }
        List<ViralSeed> fresh = fetch(keyword);
        cache.put(keyword, fresh, CACHE_TTL.toNanos());
        return fresh;
    }

    private List<ViralSeed> fetch(String keyword) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("keyword", keyword);
        ResponseEntity<String> response = proxy.forwardGet(
                "/v1/viral-seeds", "/api/marketing/viral-seeds", query, true, 60L);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("viral-seeds lookup failed keyword={} status={}", keyword, response.getStatusCode().value());
            return Collections.emptyList();
        }
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                log.warn("viral-seeds payload not an array keyword={}", keyword);
                return Collections.emptyList();
            }
            List<ViralSeed> result = new ArrayList<>(root.size());
            for (JsonNode element : root) {
                if (!element.isObject()) {
                    continue;
                }
                String author = textOrNull(element, "author");
                if (author == null || author.isBlank()) {
                    continue;
                }
                result.add(new ViralSeed(author, textOrNull(element, "primaryPlatform"),
                        AuthorProfileLinkResolver.extractProfileUrl(element)));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse viral-seeds payload keyword={}", keyword, e);
            return Collections.emptyList();
        }
    }

    private static String textOrNull(JsonNode element, String field) {
        JsonNode v = element.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }
}
