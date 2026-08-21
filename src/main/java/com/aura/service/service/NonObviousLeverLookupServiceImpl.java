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
import java.util.List;

@Slf4j
@Service
public class NonObviousLeverLookupServiceImpl implements NonObviousLeverLookupService {

    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AuraMathProxyService proxy;
    private final ObjectMapper objectMapper;
    private final TtlCache<List<LeverFinding>> cache = new TtlCache<>(1024);

    public NonObviousLeverLookupServiceImpl(AuraMathProxyService proxy, ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<LeverFinding> getNonObviousLevers(long entityId) {
        String cacheKey = Long.toString(entityId);
        List<LeverFinding> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        ResponseEntity<String> response = proxy.forwardMarketingGet(
                "/v1/marketing/entity/{entityId}/nonobvious-levers",
                "/api/marketing/entity/" + entityId + "/nonobvious-levers",
                60);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("nonobvious-levers lookup failed entityId={} status={}", entityId,
                    response.getStatusCode().value());
            return Collections.emptyList();
        }

        List<LeverFinding> parsed = parse(response.getBody(), entityId);
        cache.put(cacheKey, parsed, CACHE_TTL.toNanos());
        return parsed;
    }

    private List<LeverFinding> parse(String body, long entityId) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"ok".equals(root.path("status").asText(""))) {
                return Collections.emptyList();
            }
            JsonNode findings = root.get("findings");
            if (findings == null || !findings.isArray()) {
                return Collections.emptyList();
            }

            List<LeverFinding> result = new ArrayList<>();
            for (JsonNode f : findings) {
                String featureName = textOrNull(f, "feature_name");
                String direction = textOrNull(f, "direction");
                JsonNode pValue = f.get("p_value");
                JsonNode fdrQValue = f.get("fdr_q_value");
                JsonNode nEntities = f.get("n_entities");
                if (featureName == null || direction == null
                        || pValue == null || !pValue.isNumber()
                        || fdrQValue == null || !fdrQValue.isNumber()
                        || nEntities == null || !nEntities.isNumber()) {
                    continue;
                }
                result.add(new LeverFinding(featureName, direction,
                        pValue.asDouble(), fdrQValue.asDouble(), nEntities.asLong()));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse nonobvious-levers payload entityId={}", entityId, e);
            return Collections.emptyList();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }
}
