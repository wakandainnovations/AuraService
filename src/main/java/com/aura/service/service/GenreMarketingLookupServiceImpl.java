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

@Slf4j
@Service
public class GenreMarketingLookupServiceImpl implements GenreMarketingLookupService {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AuraMathProxyService proxy;
    private final ObjectMapper objectMapper;
    private final TtlCache<GenreReach> cache = new TtlCache<>(256);

    public GenreMarketingLookupServiceImpl(AuraMathProxyService proxy, ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    @Override
    public GenreReach getGenreReach(String genre) {
        if (genre == null || genre.isBlank()) {
            return null;
        }
        GenreReach cached = cache.get(genre);
        if (cached != null) {
            return cached;
        }
        Long totalViewers = fetchTotalViewers(genre);
        String topChannel = fetchTopChannel(genre);
        if (totalViewers == null && topChannel == null) {
            return null;
        }
        GenreReach fresh = new GenreReach(totalViewers, topChannel);
        cache.put(genre, fresh, CACHE_TTL.toNanos());
        return fresh;
    }

    private Long fetchTotalViewers(String genre) {
        ResponseEntity<String> response = proxy.forwardMarketingGet(
                "/v1/marketing/genre/{genre}/potential-viewers",
                "/api/marketing/genre/" + encodeSegment(genre) + "/potential-viewers",
                60);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("genre potential-viewers lookup failed genre={} status={}", genre, response.getStatusCode().value());
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode totalViewers = root.get("totalViewers");
            return totalViewers != null && totalViewers.isNumber() ? totalViewers.asLong() : null;
        } catch (Exception e) {
            log.warn("Failed to parse genre potential-viewers payload genre={}", genre, e);
            return null;
        }
    }

    private String fetchTopChannel(String genre) {
        ResponseEntity<String> response = proxy.forwardMarketingGet(
                "/v1/marketing/genre/{genre}/channel-strategy",
                "/api/marketing/genre/" + encodeSegment(genre) + "/channel-strategy",
                60);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("genre channel-strategy lookup failed genre={} status={}", genre, response.getStatusCode().value());
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode topChannel = root.get("topChannel");
            return topChannel != null && topChannel.isTextual() && !topChannel.asText().isBlank()
                    ? topChannel.asText() : null;
        } catch (Exception e) {
            log.warn("Failed to parse genre channel-strategy payload genre={}", genre, e);
            return null;
        }
    }

    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
