package com.aura.service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Masks a payload by serializing it to a JSON tree and rewriting every leaf, so the logic is the same
 * for any feature's response shape (no per-type masking code). See {@link PreviewMaskingService} for
 * the guarantees.
 */
@Service
public class PreviewMaskingServiceImpl implements PreviewMaskingService {

    /** Replacement for every textual leaf — a fixed token, so not one original character can leak. */
    static final String MASKED_TEXT = "★★★★★"; // ★★★★★

    /** Lists are truncated to at most this many (masked) elements, as a teaser. */
    static final int TEASER_LENGTH = 1;

    // Self-contained mapper: the module is registered so temporal fields (Instant, LocalDate) serialize
    // to strings we can mask rather than blowing up. Masking never depends on app-wide Jackson config.
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public Object mask(Object payload) {
        if (payload == null) {
            return null;
        }
        return maskNode(mapper.valueToTree(payload));
    }

    private Object maskNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            // Keep the keys (shape is not secret) but mask every value.
            Map<String, Object> masked = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> masked.put(e.getKey(), maskNode(e.getValue())));
            return masked;
        }
        if (node.isArray()) {
            List<Object> masked = new ArrayList<>();
            for (int i = 0; i < node.size() && i < TEASER_LENGTH; i++) {
                masked.add(maskNode(node.get(i)));
            }
            return masked;
        }
        if (node.isNumber()) {
            return bucket(node.asDouble());
        }
        if (node.isBoolean()) {
            // A boolean carries a real value as much as a number does — drop it.
            return null;
        }
        // Textual or any other scalar.
        return MASKED_TEXT;
    }

    /**
     * Maps a number to a coarse, digit-free magnitude bucket. The result contains no digits at all, so
     * the exact value can neither be reconstructed from nor accidentally equal the preview — satisfying
     * "bucketed, never exact" while guaranteeing no numeric leak.
     */
    static String bucket(double value) {
        double abs = Math.abs(value);
        if (abs == 0) {
            return "none";
        }
        if (abs < 10) {
            return "a handful";
        }
        if (abs < 100) {
            return "dozens";
        }
        if (abs < 1_000) {
            return "hundreds";
        }
        if (abs < 10_000) {
            return "thousands";
        }
        if (abs < 1_000_000) {
            return "many thousands";
        }
        return "millions+";
    }
}
