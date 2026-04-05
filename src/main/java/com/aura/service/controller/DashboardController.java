package com.aura.service.controller;

import com.aura.service.dto.*;
import com.aura.service.enums.Platform;
import com.aura.service.enums.TimePeriod;
import com.aura.service.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    
    private final DashboardService dashboardService;
    
    @GetMapping("/{entityId}/stats")
    public ResponseEntity<EntityStatsResponse> getStats(@PathVariable Long entityId) {
        EntityStatsResponse response = dashboardService.getEntityStats(entityId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{entityId}/stats/avg")
    public ResponseEntity<EntityStatsAvgResponse> getStatsAvg(@PathVariable Long entityId) {
        EntityStatsAvgResponse response = dashboardService.getEntityStatsAvg(entityId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/cluster/stats/avg")
    public ResponseEntity<EntityStatsAvgResponse> getStatsAvgMultiple(@RequestParam List<Long> entityIds) {
        EntityStatsAvgResponse response = dashboardService.getEntityStatsAvg(entityIds);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{entityId}/competitor-snapshot")
    public ResponseEntity<List<CompetitorSnapshot>> getCompetitorSnapshot(@PathVariable Long entityId) {
        List<CompetitorSnapshot> response = dashboardService.getCompetitorSnapshot(entityId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/sentiment-over-time")
    public ResponseEntity<SentimentOverTimeResponse> getSentimentOverTime(
            @RequestParam TimePeriod period,
            @RequestParam List<Long> entityIds
    ) {
        SentimentOverTimeResponse response = dashboardService.getSentimentOverTime(period, entityIds);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{entityId}/platform-mentions")
    public ResponseEntity<Map<String, Map<String, Long>>> getPlatformMentions(@PathVariable Long entityId) {
        Map<String, Map<String, Long>> response = dashboardService.getPlatformMentions(entityId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{entityId}/mentions")
    public ResponseEntity<Page<MentionResponse>> getMentions(
            @PathVariable Long entityId,
            @RequestParam(required = false) Platform platform,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Integer.MAX_VALUE) int size
    ) {
        Page<MentionResponse> response = dashboardService.getMentions(
                entityId, platform, page, size
        );
        return ResponseEntity.ok(response);
    }
}
