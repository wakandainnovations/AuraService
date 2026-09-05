package com.aura.service.controller;

import com.aura.service.dto.*;
import com.aura.service.enums.Platform;
import com.aura.service.enums.RecommendedActionStatus;
import com.aura.service.enums.ReviewAspectCategory;
import com.aura.service.enums.TimePeriod;
import com.aura.service.licensing.Feature;
import com.aura.service.service.AudiencePulseAspectsService;
import com.aura.service.service.CommandCenterSummaryService;
import com.aura.service.service.DashboardService;
import com.aura.service.service.EntitlementService;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.RecommendedActionsService;
import com.aura.service.service.ReviewAspectBreakdownService;
import com.aura.service.service.TopSpreaderContentService;
import com.aura.service.service.TopSpreaderInsightsService;
import com.aura.service.service.UserEntityViewService;
import com.aura.service.service.WhatsChangedService;
import com.aura.service.service.WhatsNewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import jakarta.validation.Valid;
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
    private final CommandCenterSummaryService commandCenterSummaryService;
    private final AudiencePulseAspectsService audiencePulseAspectsService;
    private final RecommendedActionsService recommendedActionsService;
    private final TopSpreaderContentService topSpreaderContentService;
    private final TopSpreaderInsightsService topSpreaderInsightsService;
    private final ReviewAspectBreakdownService reviewAspectBreakdownService;
    private final EntitlementService entitlementService;

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
    
    @GetMapping("/sentiment-over-time-range")
    public ResponseEntity<SentimentOverTimeResponse> getSentimentOverTimeForRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam List<Long> entityIds
    ) {
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.badRequest().build();
        }
        assertOwned(entityIds);
        SentimentOverTimeResponse response = dashboardService.getSentimentOverTimeForRange(
                startDate, endDate, entityIds);
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

    @GetMapping("/{entityId}/audience-pulse")
    public ResponseEntity<AudiencePulseResponse> getAudiencePulse(@PathVariable Long entityId) {
        assertOwned(entityId);
        AudiencePulseResponse response = dashboardService.getAudiencePulse(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/promotional-mix")
    public ResponseEntity<PromotionalMixResponse> getPromotionalMix(@PathVariable Long entityId) {
        assertOwned(entityId);
        PromotionalMixResponse response = dashboardService.getPromotionalMix(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/author-type-breakdown")
    public ResponseEntity<AuthorTypeBreakdownResponse> getAuthorTypeBreakdown(@PathVariable Long entityId) {
        assertOwned(entityId);
        AuthorTypeBreakdownResponse response = dashboardService.getAuthorTypeBreakdown(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/content-intent-breakdown")
    public ResponseEntity<ContentIntentBreakdownResponse> getContentIntentBreakdown(@PathVariable Long entityId) {
        assertOwned(entityId);
        ContentIntentBreakdownResponse response = dashboardService.getContentIntentBreakdown(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/topic-category-breakdown")
    public ResponseEntity<TopicCategoryBreakdownResponse> getTopicCategoryBreakdown(@PathVariable Long entityId) {
        assertOwned(entityId);
        TopicCategoryBreakdownResponse response = dashboardService.getTopicCategoryBreakdown(entityId);
        return ResponseEntity.ok(response);
    }

    /**
     * Post count and average sentiment score per fixed review aspect (Music/Songs, Direction,
     * Acting/Cast Performance, Story, Screenplay, Lead Pair, Runtime, First Half, Second Half, Climax,
     * VFX, Other). {@code refresh=true} additionally classifies this entity's own pending backlog of
     * not-yet-categorized posts before returning; otherwise relies on the background sweep in
     * {@link ReviewAspectBreakdownService}.
     */
    @GetMapping("/{entityId}/review-aspect-breakdown")
    public ResponseEntity<ReviewAspectBreakdownResponse> getReviewAspectBreakdown(
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        assertOwned(entityId);
        ReviewAspectBreakdownResponse response = reviewAspectBreakdownService.getBreakdown(entityId, refresh);
        return ResponseEntity.ok(response);
    }

    /**
     * Triggers an unbounded catch-up of this entity's entire not-yet-classified review-aspect backlog
     * in the background and returns immediately (202) — unlike {@code review-aspect-breakdown}'s own
     * {@code refresh=true}, which is capped per call and runs on the request thread. Open to any owner
     * of the entity, same as every other endpoint here; see {@link ReviewAspectBreakdownService#triggerBackfill}
     * for how a repeated trigger against an already-running backfill is deduped.
     */
    @PostMapping("/{entityId}/review-aspect-backfill")
    public ResponseEntity<ReviewAspectBackfillResponse> triggerReviewAspectBackfill(@PathVariable Long entityId) {
        assertOwned(entityId);
        ReviewAspectBackfillResponse response = reviewAspectBreakdownService.triggerBackfill(entityId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{entityId}/top-spreaders/content")
    public ResponseEntity<TopSpreaderContentResponse> getTopSpreaderContent(
            @PathVariable Long entityId,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int spreaderLimit,
            @RequestParam(defaultValue = "5") @Min(1) @Max(10) int postsPerSpreader) {
        assertOwned(entityId);
        TopSpreaderContentResponse response = topSpreaderContentService.getTopSpreaderContent(
                entityId, language, spreaderLimit, postsPerSpreader);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/top-spreaders/insights")
    public ResponseEntity<TopSpreaderInsightsResponse> getTopSpreaderInsights(
            @PathVariable Long entityId,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int spreaderLimit,
            @RequestParam(defaultValue = "5") @Min(1) @Max(10) int postsPerSpreader,
            @RequestParam(defaultValue = "false") boolean refresh) {
        assertOwned(entityId);
        TopSpreaderInsightsResponse response = topSpreaderInsightsService.getInsights(
                entityId, language, spreaderLimit, postsPerSpreader, refresh);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/ai-summary")
    public ResponseEntity<AiSummaryResponse> getAiSummary(
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        assertOwned(entityId);
        AiSummaryResponse response = commandCenterSummaryService.getAiSummary(entityId, refresh);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/todays-highlights")
    public ResponseEntity<TodaysHighlightsResponse> getTodaysHighlights(
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        assertOwned(entityId);
        TodaysHighlightsResponse response = commandCenterSummaryService.getTodaysHighlights(entityId, refresh);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/audience-pulse-aspects")
    public ResponseEntity<AudiencePulseAspectsResponse> getAudiencePulseAspects(
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        assertOwned(entityId);
        AudiencePulseAspectsResponse response = audiencePulseAspectsService.getAspects(entityId, refresh);
        return ResponseEntity.ok(response);
    }

    /**
     * Gated behind {@link Feature#CRISIS} because the response now carries {@link
     * RecommendedActionsResponse#getSituationRecommendation()} - the same GOLD-tier "Social Buzz
     * Situation" analysis {@code /api/crisis/situation-recommendation/{entityId}} exposes on its own,
     * folded in here so the marketing team gets both the tactical action list and the reactive
     * burst/precedent read in one call. A non-entitled caller gets the whole response - including the
     * previously-ungated action list - back as a masked preview (see {@link EntitlementService#evaluate}),
     * not just the situation portion; {@link com.aura.service.service.PreviewMaskingService} has no
     * per-field granularity.
     */
    @GetMapping("/{entityId}/recommended-actions")
    public EntitledResponse<RecommendedActionsResponse> getRecommendedActions(
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        assertOwned(entityId);
        return entitlementService.evaluate(Feature.CRISIS,
                () -> recommendedActionsService.getRecommendedActions(entityId, refresh));
    }

    /**
     * Every recommended action ever generated for this entity - past and present, each carrying
     * whatever status the marketing team last set - so the team can see what's already been handled
     * (DONE), what's been ruled out (IRRELEVANT), and what's still open (ACTIVE), independent of
     * today's execution window. Optionally narrowed to a single status via {@code status}.
     */
    @GetMapping("/{entityId}/recommended-actions/all")
    public ResponseEntity<RecommendedActionsResponse> getAllRecommendedActions(
            @PathVariable Long entityId,
            @RequestParam(required = false) RecommendedActionStatus status) {
        assertOwned(entityId);
        RecommendedActionsResponse response = recommendedActionsService.getAllRecommendedActions(entityId, status);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{entityId}/recommended-actions/{actionId}/status")
    public ResponseEntity<RecommendedActionItem> updateRecommendedActionStatus(
            @PathVariable Long entityId,
            @PathVariable String actionId,
            @Valid @RequestBody UpdateRecommendedActionStatusRequest request) {
        assertOwned(entityId);
        RecommendedActionItem updated = recommendedActionsService.updateActionStatus(entityId, actionId, request.getStatus());
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{entityId}/movie-health")
    public ResponseEntity<MovieHealthResponse> getMovieHealth(@PathVariable Long entityId) {
        assertOwned(entityId);
        MovieHealthResponse response = dashboardService.getMovieHealth(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/buzz")
    public ResponseEntity<BuzzResponse> getBuzz(@PathVariable Long entityId) {
        assertOwned(entityId);
        BuzzResponse response = dashboardService.getBuzz(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/sentiment")
    public ResponseEntity<MovieSentimentResponse> getSentiment(@PathVariable Long entityId) {
        assertOwned(entityId);
        MovieSentimentResponse response = dashboardService.getSentiment(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/reach")
    public ResponseEntity<ReachResponse> getReach(@PathVariable Long entityId) {
        assertOwned(entityId);
        ReachResponse response = dashboardService.getReach(entityId);
        return ResponseEntity.ok(response);
    }

    /** Same as {@link #getReach}, computed by joining the four raw platform tables directly on their {@code entity} column. */
    @GetMapping("/{entityId}/reach-direct")
    public ResponseEntity<ReachResponse> getReachDirect(@PathVariable Long entityId) {
        assertOwned(entityId);
        ReachResponse response = dashboardService.getReachDirect(entityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{entityId}/awareness")
    public ResponseEntity<AwarenessResponse> getAwareness(@PathVariable Long entityId) {
        assertOwned(entityId);
        AwarenessResponse response = dashboardService.getAwareness(entityId);
        return ResponseEntity.ok(response);
    }

    /**
     * Also the spot-check/drill-down path for every per-post classification breakdown panel
     * (review-aspect, topic-category, content-intent, author-type, audience-pulse region): pass the
     * exact {@code category}/{@code region} value shown in the corresponding breakdown response to
     * list the underlying posts and their raw {@code content}, so a caller can verify the classifier
     * rather than trust the aggregate blindly. {@code topicCategory}/{@code contentIntent}/
     * {@code authorType}/{@code region} match case-insensitively since that taxonomy is
     * upstream-owned, not a fixed enum in this codebase.
     */
    @GetMapping("/{entityId}/mentions")
    public ResponseEntity<Page<MentionResponse>> getMentions(
            @PathVariable Long entityId,
            @RequestParam(required = false) Platform platform,
            @RequestParam(required = false) ReviewAspectCategory reviewAspectCategory,
            @RequestParam(required = false) String topicCategory,
            @RequestParam(required = false) String contentIntent,
            @RequestParam(required = false) String authorType,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Integer.MAX_VALUE) int size,
            @RequestParam(required = false) Long ownerId,
            @AuthenticationPrincipal UserDetails principal
    ) {
        // Admins may scope to a specific user via ownerId (the entity must belong to them); a
        // non-admin passing ownerId is rejected (403). Otherwise this is the normal ownership check.
        entityAccessService.assertAccessible(entityId, ownerId);
        Page<MentionResponse> response = dashboardService.getMentions(
                entityId, platform, reviewAspectCategory, topicCategory, contentIntent, authorType, region, page, size
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
            @RequestParam(defaultValue = "" + Integer.MAX_VALUE) int size,
            @RequestParam(required = false) Long ownerId
    ) {
        // Gate once up front so a non-admin passing ownerId gets 403 regardless of the ids, then
        // require every entity in the cluster to be accessible under the (optional) owner scope.
        entityAccessService.requireAdminToScopeByOwner(ownerId);
        if (entityIds != null) {
            entityIds.forEach(id -> entityAccessService.assertAccessible(id, ownerId));
        }
        Page<MentionResponse> response = dashboardService.getClusterMentions(
                entityIds, platform, page, size
        );
        return ResponseEntity.ok(response);
    }
}
