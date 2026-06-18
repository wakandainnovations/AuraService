package com.aura.service.service;

import com.aura.service.entity.EntityKeyword;
import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MarketingAggregationService {

    private final ManagedEntityRepository entityRepository;
    private final AuraMathProxyService proxy;
    private final ObjectMapper objectMapper;

    public MarketingAggregationService(ManagedEntityRepository entityRepository,
                                       AuraMathProxyService proxy,
                                       ObjectMapper objectMapper) {
        this.entityRepository = entityRepository;
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    public Object getAggregatedTopSpreaders(String language, String industry,
                                            String state, String genre,
                                            Long entityId, boolean groupByKeyword) {
        return aggregateByKeyword("top-spreaders", language, industry, state, genre, entityId, groupByKeyword);
    }

    public Object getAggregatedViralSeeds(String language, String industry,
                                          String state, String genre,
                                          Long entityId, boolean groupByKeyword) {
        return aggregateByKeyword("viral-seeds", language, industry, state, genre, entityId, groupByKeyword);
    }

    public Object getAggregatedAspectDrivers(String language, String industry,
                                             String state, String genre,
                                             Long entityId, boolean groupByKeyword) {
        return aggregateByKeyword("aspect-drivers", language, industry, state, genre, entityId, groupByKeyword);
    }

    public Object getAggregatedBrandEvangelists(String language, String industry,
                                                String state, String genre,
                                                Long entityId, boolean groupByKeyword) {
        return aggregateByKeyword("brand-evangelists", language, industry, state, genre, entityId, groupByKeyword);
    }

    public Object getAggregatedGenreData(String subType, String language, String industry,
                                         String state, String genre,
                                         Long entityId, boolean groupByGenre) {
        List<EntityKeyword> keywords = findKeywords(language, industry, state, genre, entityId);
        if (keywords.isEmpty()) {
            return groupByGenre ? Map.of() : List.of();
        }

        // genre is stored as a comma-separated list on each keyword row, so split
        // it back into the individual genres before collecting the distinct set.
        Set<String> genres = keywords.stream()
                .map(EntityKeyword::getGenre)
                .filter(g -> g != null && !g.isBlank())
                .flatMap(g -> Arrays.stream(g.split(",")))
                .map(String::trim)
                .filter(g -> !g.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (genres.isEmpty()) {
            return groupByGenre ? Map.of() : List.of();
        }

        String upstreamSuffix = switch (subType) {
            case "potential-viewers" -> "/potential-viewers";
            case "super-spreaders" -> "/super-spreaders";
            case "channel-strategy" -> "/channel-strategy";
            default -> throw new IllegalArgumentException("Unknown genre sub-type: " + subType);
        };

        if (groupByGenre) {
            Map<String, JsonNode> grouped = new LinkedHashMap<>();
            for (String g : genres) {
                JsonNode data = fetchGenreData(g, upstreamSuffix);
                if (data != null) {
                    grouped.put(g, data);
                }
            }
            return grouped;
        }

        ArrayNode merged = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (String g : genres) {
            JsonNode data = fetchGenreData(g, upstreamSuffix);
            if (data != null && data.isArray()) {
                for (JsonNode element : data) {
                    String dedup = deduplicationKey(element);
                    if (seen.add(dedup)) {
                        merged.add(element);
                    }
                }
            } else if (data != null) {
                String dedup = deduplicationKey(data);
                if (seen.add(dedup)) {
                    merged.add(data);
                }
            }
        }
        return merged;
    }

    List<EntityKeyword> findKeywords(String language, String industry,
                                     String state, String genre, Long entityId) {
        // genre is stored comma-separated on each keyword row; match it as a whole
        // token by wrapping the requested value in commas to form the LIKE pattern.
        // Genres are stored in whatever case the entity was created with, so lower-case
        // the pattern and compare against LOWER(genre) in the query for a case-insensitive match.
        String genrePattern = (genre == null || genre.isBlank()) ? null : "%," + genre.toLowerCase() + ",%";
        // language/industry/state are matched case-insensitively too, but we lower-case the
        // value here rather than wrapping the bind parameter in LOWER(...) in the query:
        // Postgres can't infer the type of a bound arg inside lower(?) and falls back to
        // bytea ("function lower(bytea) does not exist"). The query LOWER()s only the column.
        return entityRepository.findKeywordsByFilters(
                lowerOrNull(language), lowerOrNull(industry), lowerOrNull(state), genrePattern, entityId);
    }

    private static String lowerOrNull(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private Object aggregateByKeyword(String category, String language, String industry,
                                      String state, String genre,
                                      Long entityId, boolean groupByKeyword) {
        List<EntityKeyword> keywords = findKeywords(language, industry, state, genre, entityId);
        if (keywords.isEmpty()) {
            return groupByKeyword ? Map.of() : List.of();
        }

        List<String> distinctKeywords = keywords.stream()
                .map(EntityKeyword::getKeyword)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .collect(Collectors.toList());

        if (groupByKeyword) {
            Map<String, JsonNode> grouped = new LinkedHashMap<>();
            for (String keyword : distinctKeywords) {
                JsonNode data = fetchCategoryData(category, keyword);
                if (data != null) {
                    grouped.put(keyword, data);
                }
            }
            return grouped;
        }

        ArrayNode merged = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (String keyword : distinctKeywords) {
            JsonNode data = fetchCategoryData(category, keyword);
            if (data != null && data.isArray()) {
                for (JsonNode element : data) {
                    String dedup = deduplicationKey(element);
                    if (seen.add(dedup)) {
                        merged.add(element);
                    }
                }
            } else if (data != null) {
                String dedup = deduplicationKey(data);
                if (seen.add(dedup)) {
                    merged.add(data);
                }
            }
        }
        return merged;
    }

    private JsonNode fetchCategoryData(String category, String keyword) {
        String upstreamPath = switch (category) {
            case "top-spreaders" -> "/api/marketing/top-50-spreaders/" + encodeSegment(keyword);
            case "viral-seeds" -> "/api/marketing/viral-seeds";
            case "aspect-drivers" -> "/api/marketing/aspect-drivers/" + encodeSegment(keyword);
            case "brand-evangelists" -> "/api/marketing/brand-evangelists/" + encodeSegment(keyword);
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };

        String wrapperPath = "/api/marketing/aggregate/" + category;

        ResponseEntity<String> response;
        if ("viral-seeds".equals(category)) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("keyword", keyword);
            response = proxy.forwardGet(wrapperPath, upstreamPath, query, true, null);
        } else {
            response = proxy.forwardGet(wrapperPath, upstreamPath, null, true, null);
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("aggregate fetch failed category={} keyword={} status={}",
                    category, keyword, response.getStatusCode().value());
            return null;
        }

        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Failed to parse aggregate response category={} keyword={}", category, keyword, e);
            return null;
        }
    }

    private JsonNode fetchGenreData(String genre, String upstreamSuffix) {
        String upstreamPath = "/api/marketing/genre/" + encodeSegment(genre) + upstreamSuffix;
        String wrapperPath = "/api/marketing/aggregate/genre" + upstreamSuffix;

        ResponseEntity<String> response = proxy.forwardGet(
                wrapperPath, upstreamPath, null, true, null);

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("genre aggregate fetch failed genre={} suffix={} status={}",
                    genre, upstreamSuffix, response.getStatusCode().value());
            return null;
        }

        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Failed to parse genre response genre={} suffix={}", genre, upstreamSuffix, e);
            return null;
        }
    }

    private String deduplicationKey(JsonNode element) {
        if (element.isTextual()) {
            return element.asText();
        }
        if (element.isObject()) {
            for (String field : new String[]{"globalUserId", "userId", "author", "username", "id"}) {
                JsonNode v = element.get(field);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    return v.asText();
                }
            }
        }
        return element.toString();
    }

    private static String encodeSegment(String segment) {
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
