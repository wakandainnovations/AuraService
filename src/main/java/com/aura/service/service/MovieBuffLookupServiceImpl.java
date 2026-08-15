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
public class MovieBuffLookupServiceImpl implements MovieBuffLookupService {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AuraMathProxyService proxy;
    private final ObjectMapper objectMapper;
    private final TtlCache<List<MovieBuff>> cache = new TtlCache<>(1024);

    public MovieBuffLookupServiceImpl(AuraMathProxyService proxy, ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<MovieBuff> getMovieBuffs(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        List<MovieBuff> cached = cache.get(keyword);
        if (cached != null) {
            return cached;
        }
        List<MovieBuff> fresh = fetch(keyword);
        cache.put(keyword, fresh, CACHE_TTL.toNanos());
        return fresh;
    }

    private List<MovieBuff> fetch(String keyword) {
        ResponseEntity<String> response = proxy.forwardMarketingGet(
                "/v1/marketing/movie-buffs/{keyword}",
                "/api/marketing/brand-evangelists/" + encodeSegment(keyword),
                60);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("movie-buffs lookup failed keyword={} status={}", keyword, response.getStatusCode().value());
            return Collections.emptyList();
        }
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode evangelists = root.get("evangelists");
            if (evangelists == null || !evangelists.isArray()) {
                return Collections.emptyList();
            }
            List<MovieBuff> result = new ArrayList<>(evangelists.size());
            for (JsonNode element : evangelists) {
                if (!element.isObject()) {
                    continue;
                }
                String author = textOrNull(element, "author");
                if (author == null || author.isBlank()) {
                    continue;
                }
                result.add(new MovieBuff(author, textOrNull(element, "influenceTier")));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse movie-buffs payload keyword={}", keyword, e);
            return Collections.emptyList();
        }
    }

    private static String textOrNull(JsonNode element, String field) {
        JsonNode v = element.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
