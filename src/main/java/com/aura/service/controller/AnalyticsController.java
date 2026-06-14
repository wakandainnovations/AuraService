package com.aura.service.controller;

import com.aura.service.service.AnalyticsService;
import com.aura.service.service.EntityAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final EntityAccessService entityAccessService;
    private final Map<Long, JsonNode> cache = new HashMap<>();

    @GetMapping("/{movieId}")
    public ResponseEntity<Map<String, Object>> getPrediction(@PathVariable Long movieId) {
        // The shared cache is global, so enforce ownership before serving — including cache hits.
        entityAccessService.assertOwnedByCurrentUser(movieId);
        if (cache.containsKey(movieId)) {
            Map<String, Object> response = new HashMap<>();
            response.put("movieId", movieId);
            response.put("predictedBoxOffice", cache.get(movieId));
            return ResponseEntity.ok(response);
        }

        JsonNode prediction = analyticsService.getAnalytics(movieId);
        cache.put(movieId, prediction);
        Map<String, Object> response = new HashMap<>();
        response.put("movieId", movieId);
        response.put("predictedBoxOffice", prediction);
        return ResponseEntity.ok(response);
    }
}
