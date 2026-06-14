package com.aura.service.controller;

import com.aura.service.dto.*;
import com.aura.service.enums.Platform;
import com.aura.service.enums.TimePeriod;
import com.aura.service.service.DashboardService;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.UserEntityViewService;
import com.aura.service.service.WhatsChangedService;
import com.aura.service.service.WhatsNewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final UserEntityViewService userEntityViewService;
    private final WhatsChangedService whatsChangedService;
    private final WhatsNewService whatsNewService;
    private final EntityAccessService entityAccessService;

    /** Reject (404) any entity the caller doesn't own before any dashboard data is read. */
    private void assertOwned(Long entityId) {
        entityAccessService.assertOwnedByCurrentUser(entityId);
    }

    private void assertOwned(List<Long> entityIds) {
        if (entityIds != null) {
            entityIds.forEach(this::assertOwned);
        }
    }

    @GetMapping("/{entityId}/stats")
    public ResponseEntity<EntityStatsResponse> getStats(
            @PathVariable Long entityId,
            @AuthenticationPrincipal UserDetails principal
    ) {
        assertOwned(entityId);
        EntityStatsResponse response = dashboardService.getEntityStats(entityId);
        if (principal != null) {
            userEntityViewService.recordView(principal.getUsername(), entityId);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/last-seen")
    public ResponseEntity<Map<String, Instant>> getLastSeen(
            @PathVariable Long entityId,
            @AuthenticationPrincipal UserDetails principal
    ) {
        assertOwned(entityId);
        Instant lastSeenAt = userEntityViewService
                .findLastSeen(principal.getUsername(), entityId)
                .orElse(null);
        Map<String, Instant> body = new java.util.HashMap<>();
        body.put("lastSeenAt", lastSeenAt);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{entityId}/whats-changed")
    public ResponseEntity<WhatsChangedResponse> getWhatsChanged(
            @PathVariable Long entityId,
            @AuthenticationPrincipal UserDetails principal
    ) {
        assertOwned(entityId);
        WhatsChangedResponse response = whatsChangedService.computeDelta(
                principal.getUsername(), entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/whats-new")
    public ResponseEntity<List<WhatsNewCard>> getWhatsNew(
            @PathVariable Long entityId,
            @AuthenticationPrincipal UserDetails principal
    ) {
        assertOwned(entityId);
        List<WhatsNewCard> cards = whatsNewService.getCards(
                principal.getUsername(), entityId);
        return ResponseEntity.ok(cards);
    }

    @GetMapping("/cluster/stats")
    public ResponseEntity<EntityStatsResponse> getClusterStats(@RequestParam List<Long> entityIds) {
        assertOwned(entityIds);
        EntityStatsResponse response = dashboardService.getClusterStats(entityIds);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/stats/avg")
    public ResponseEntity<EntityStatsAvgResponse> getStatsAvg(@PathVariable Long entityId) {
        assertOwned(entityId);
        EntityStatsAvgResponse response = dashboardService.getEntityStatsAvg(entityId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/cluster/stats/avg")
    public ResponseEntity<EntityStatsAvgResponse> getStatsAvgMultiple(@RequestParam List<Long> entityIds) {
        assertOwned(entityIds);
        EntityStatsAvgResponse response = dashboardService.getEntityStatsAvg(entityIds);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{entityId}/competitor-snapshot")
    public ResponseEntity<List<CompetitorSnapshot>> getCompetitorSnapshot(@PathVariable Long entityId) {
        assertOwned(entityId);
        List<CompetitorSnapshot> response = dashboardService.getCompetitorSnapshot(entityId);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{entityId}/sentiment-delta")
    public ResponseEntity<SentimentDeltaResponse> getSentimentDelta(
            @PathVariable Long entityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "7") int windowDays
    ) {
        if (!fromDate.isBefore(toDate)) {
            return ResponseEntity.badRequest().build();
        }
        if (windowDays < 1 || windowDays > 30) {
            return ResponseEntity.badRequest().build();
        }
        assertOwned(entityId);
        SentimentDeltaResponse response = dashboardService.getSentimentDelta(
                entityId, fromDate, toDate, windowDays);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/checkpoint-impact")
    public ResponseEntity<CheckpointImpactResponse> getCheckpointImpact(
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int windowDays
    ) {
        if (windowDays < 1 || windowDays > 30) {
            return ResponseEntity.badRequest().build();
        }
        assertOwned(entityId);
        CheckpointImpactResponse response = dashboardService.getCheckpointImpact(entityId, windowDays);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/checkpoint-trend")
    public ResponseEntity<CheckpointTrendResponse> getCheckpointTrend(
            @PathVariable Long entityId
    ) {
        assertOwned(entityId);
        CheckpointTrendResponse response = dashboardService.getCheckpointTrend(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/sentiment-over-time")
    public ResponseEntity<SentimentOverTimeResponse> getSentimentOverTime(
            @RequestParam TimePeriod period,
            @RequestParam List<Long> entityIds
    ) {
        assertOwned(entityIds);
        SentimentOverTimeResponse response = dashboardService.getSentimentOverTime(period, entityIds);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{entityId}/platform-mentions")
    public ResponseEntity<Map<String, Map<String, Long>>> getPlatformMentions(@PathVariable Long entityId) {
        assertOwned(entityId);
        Map<String, Map<String, Long>> response = dashboardService.getPlatformMentions(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/cluster/platform-mentions")
    public ResponseEntity<Map<String, Map<String, Long>>> getPlatformMentionsForCluster(@RequestParam List<Long> entityIds) {
        assertOwned(entityIds);
        Map<String, Map<String, Long>> response = dashboardService.getPlatformMentionsForCluster(entityIds);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{entityId}/hourly-activity")
    public ResponseEntity<HourlyActivityResponse> getHourlyActivity(
            @PathVariable Long entityId,
            @RequestParam TimePeriod period,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String state
    ) {
        assertOwned(entityId);
        HourlyActivityResponse response = dashboardService.getHourlyActivity(
                entityId, period, language, industry, state
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/mentions")
    public ResponseEntity<Page<MentionResponse>> getMentions(
            @PathVariable Long entityId,
            @RequestParam(required = false) Platform platform,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Integer.MAX_VALUE) int size,
            @AuthenticationPrincipal UserDetails principal
    ) {
        assertOwned(entityId);
        Page<MentionResponse> response = dashboardService.getMentions(
                entityId, platform, page, size
        );
        if (principal != null) {
            userEntityViewService.recordView(principal.getUsername(), entityId);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/cluster/mentions")
    public ResponseEntity<Page<MentionResponse>> getClusterMentions(
            @RequestParam List<Long> entityIds,
            @RequestParam(required = false) Platform platform,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Integer.MAX_VALUE) int size
    ) {
        assertOwned(entityIds);
        Page<MentionResponse> response = dashboardService.getClusterMentions(
                entityIds, platform, page, size
        );
        return ResponseEntity.ok(response);
    }
}
