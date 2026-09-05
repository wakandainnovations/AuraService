package com.aura.service.service;

import com.aura.service.dto.CompetitorSnapshot;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityMarketingReportResponse.CompetitivePositioning;
import com.aura.service.dto.EntityMarketingReportResponse.HeadlineMetrics;
import com.aura.service.dto.EntityStatsAvgResponse;
import com.aura.service.dto.EntityStatsResponse;
import com.aura.service.dto.MomentumCausalReportResponse;
import com.aura.service.dto.SentimentOverTimeResponse;
import com.aura.service.entity.EntityMarketingReportCache;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.TimePeriod;
import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.repository.EntityMarketingReportCacheRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Assembles the complete, prospect-facing {@link EntityMarketingReportResponse} for a single
 * managed entity by combining this service's own analytics ({@link DashboardService} /
 * {@link EntityService}) with the upstream AuraMath entity-report
 * ({@code GET /api/marketing/entity-report/{entityId}}).
 *
 * <p>The entity profile and headline metrics are mandatory. The entity is loaded via
 * {@link EntityService#getEntityById}, which is owner-scoped: an entity that is missing <em>or</em>
 * owned by another user surfaces as {@code 404}. Every other section is optional and degrades to
 * {@code null} on failure so a single flaky downstream never blocks a report being shown to a
 * prospect.
 *
 * <p>Assembly is expensive (a dozen-plus downstream calls, one of them an LLM call), so callers
 * should go through {@link #getReport} rather than {@link #generateReport} directly: it serves the
 * row {@link #refreshAllReports()} persists every 24 hours per entity (see
 * {@code EntityMarketingReportCache}) instead of paying that cost on every request, falling back to
 * a live {@link #generateReport} (and caching that result) only on a cache miss or {@code refresh}.
 */
@Slf4j
@Service
public class EntityMarketingReportService {

    private static final String WRAPPER_PATH = "/api/entities/{entityType}/{id}/marketing-report";

    // Same defaults DashboardController uses for /top-spreaders/content and /top-spreaders/insights,
    // so this report's spreader sections match what the dedicated endpoints would return unfiltered.
    private static final int TOP_SPREADER_LIMIT = 10;
    private static final int TOP_SPREADER_POSTS_PER_SPREADER = 5;

    // The (period, windowDays) combination the scheduled cache refresh generates - matches the
    // controller's own query-param defaults, so a caller that never overrides them always hits the
    // cache instead of falling through to a live (re-)generation.
    private static final TimePeriod DEFAULT_PERIOD = TimePeriod.DAY30;
    private static final int DEFAULT_WINDOW_DAYS = 7;

    // Once a movie is this many days past release, its marketing report has settled - the scheduled
    // refresh below stops regenerating it (see refreshOneEntityForBatch), leaving the last-generated
    // cache row (however old) as the permanent answer. getReport's own refresh=true path is untouched,
    // so a caller can still force a live regeneration for an old release if they explicitly ask for one.
    static final long STALE_RELEASE_SKIP_DAYS = 30;

    private final EntityService entityService;
    private final DashboardService dashboardService;
    private final AuraMathProxyService auraMathProxy;
    private final ObjectMapper objectMapper;
    private final MomentumCausalReportService momentumCausalReportService;
    private final CommandCenterSummaryService commandCenterSummaryService;
    private final TopSpreaderContentService topSpreaderContentService;
    private final TopSpreaderInsightsService topSpreaderInsightsService;
    private final RecommendedActionsService recommendedActionsService;
    private final AudiencePulseAspectsService audiencePulseAspectsService;
    private final EntityMarketingReportCacheRepository cacheRepository;
    private final ManagedEntityRepository managedEntityRepository;
    private final Clock clock;

    // Self-injected proxy: refreshOneEntityForBatch below must be invoked through Spring's proxy
    // (not a direct this.refreshOneEntityForBatch(...) self-call) for its @Transactional advice to
    // apply - required because refreshAllReports runs off a scheduler thread with no request-bound
    // Hibernate session, so entities loaded via ManagedEntityRepository.findAll() can't have their
    // lazy collections (e.g. ManagedEntity.keywords, read deep inside assembleForBatch) resolved
    // without one. Same pattern as ViralSeedSyncService.self - see that field's doc comment. @Lazy
    // avoids the circular-bean chicken/egg problem at construction time.
    @Autowired
    @Lazy
    private EntityMarketingReportService self;

    public EntityMarketingReportService(EntityService entityService,
                                        DashboardService dashboardService,
                                        AuraMathProxyService auraMathProxy,
                                        ObjectMapper objectMapper,
                                        MomentumCausalReportService momentumCausalReportService,
                                        CommandCenterSummaryService commandCenterSummaryService,
                                        TopSpreaderContentService topSpreaderContentService,
                                        TopSpreaderInsightsService topSpreaderInsightsService,
                                        RecommendedActionsService recommendedActionsService,
                                        AudiencePulseAspectsService audiencePulseAspectsService,
                                        EntityMarketingReportCacheRepository cacheRepository,
                                        ManagedEntityRepository managedEntityRepository,
                                        Clock clock) {
        this.entityService = entityService;
        this.dashboardService = dashboardService;
        this.auraMathProxy = auraMathProxy;
        this.objectMapper = objectMapper;
        this.momentumCausalReportService = momentumCausalReportService;
        this.commandCenterSummaryService = commandCenterSummaryService;
        this.topSpreaderContentService = topSpreaderContentService;
        this.topSpreaderInsightsService = topSpreaderInsightsService;
        this.recommendedActionsService = recommendedActionsService;
        this.audiencePulseAspectsService = audiencePulseAspectsService;
        this.cacheRepository = cacheRepository;
        this.managedEntityRepository = managedEntityRepository;
        this.clock = clock;
    }

    /**
     * The cache-first entry point controllers should call: serves the row the scheduled 24-hourly
     * refresh (see {@link #refreshAllReports()}) last persisted for this exact (entity, period,
     * windowDays) combination instead of paying for a live assembly, unless {@code refresh} is set or
     * no such row exists yet (e.g. a brand-new entity, or a non-default period/windowDays the
     * schedule doesn't generate) - either falls through to {@link #generateReport} and persists the
     * result so the next call for this combination is served from cache too.
     */
    public EntityMarketingReportResponse getReport(String entityType, Long id,
                                                   TimePeriod period, int windowDays, boolean refresh) {
        // Ownership/existence must be enforced before ever serving a cached row - the cache is keyed
        // by entity, not by caller, so skipping this on a cache hit would leak one owner's report to
        // anyone who guesses another owner's entity id.
        entityService.getEntityById(entityType, id);

        if (!refresh) {
            var cached = cacheRepository.findByEntityIdAndPeriodAndWindowDays(id, period.name(), windowDays);
            if (cached.isPresent()) {
                EntityMarketingReportResponse report = deserialize(cached.get(), id);
                if (report != null) {
                    return report;
                }
            }
        }

        EntityMarketingReportResponse report = generateReport(entityType, id, period, windowDays);
        persist(id, period, windowDays, report);
        return report;
    }

    /**
     * Refreshes the cached report for every managed entity, at {@link #DEFAULT_PERIOD}/
     * {@link #DEFAULT_WINDOW_DAYS}, so {@link #getReport} can normally serve a persisted row instead
     * of paying for the full assembly (a dozen-plus downstream calls, one of them an LLM call) on
     * request. Runs at startup and every 24 hours after that; one entity's failure is logged and
     * skipped rather than aborting the run. Uses {@link ManagedEntityRepository} and
     * {@link MomentumCausalReportService#buildReportForEntity} directly rather than the owner-scoped
     * {@link EntityService}/{@link MomentumCausalReportService#buildReport} paths, since a scheduler
     * thread has no authenticated request to check ownership against.
     */
    @Scheduled(fixedDelayString = "PT24H")
    public void refreshAllReports() {
        List<Long> entityIds = managedEntityRepository.findAll().stream().map(ManagedEntity::getId).toList();
        log.info("Refreshing marketing reports for {} entities", entityIds.size());
        for (Long entityId : entityIds) {
            try {
                self.refreshOneEntityForBatch(entityId);
            } catch (Exception e) {
                log.error("Failed to refresh marketing report for entity {}", entityId, e);
            }
        }
    }

    /**
     * Must be called via {@link #self}, not directly - see that field's doc comment. Re-fetches the
     * entity by id (rather than taking a {@link ManagedEntity} from {@link #refreshAllReports}'s
     * earlier, transaction-less {@code findAll()}) so its lazy collections - e.g.
     * {@code ManagedEntity.keywords}, read deep inside {@link #assembleForBatch} - are bound to this
     * method's own session; a detached entity's lazy collections can't be initialized just by calling
     * it from within a new transaction. Not read-only: {@link #persist} at the end saves the cache row.
     * Skips the assembly entirely (see {@link #isStaleRelease}) for a movie released more than
     * {@link #STALE_RELEASE_SKIP_DAYS} days ago - its report has settled, and re-running a dozen-plus
     * downstream calls (one an LLM call) daily for it in perpetuity isn't worth the cost.
     */
    @Transactional
    void refreshOneEntityForBatch(Long entityId) {
        ManagedEntity entity = managedEntityRepository.findById(entityId).orElse(null);
        if (entity == null || isStaleRelease(entity)) {
            return;
        }
        EntityMarketingReportResponse report = assembleForBatch(entity, DEFAULT_PERIOD, DEFAULT_WINDOW_DAYS);
        persist(entity.getId(), DEFAULT_PERIOD, DEFAULT_WINDOW_DAYS, report);
    }

    // No releaseDate on file means no evidence the movie has released - not held back, same reasoning
    // as RecommendedActionCandidateServiceImpl#isNotYetReleased.
    private boolean isStaleRelease(ManagedEntity entity) {
        LocalDate releaseDate = entity.getReleaseDate();
        if (releaseDate == null) {
            return false;
        }
        long daysSinceRelease = ChronoUnit.DAYS.between(releaseDate, LocalDate.now(clock));
        return daysSinceRelease > STALE_RELEASE_SKIP_DAYS;
    }

    private void persist(Long id, TimePeriod period, int windowDays, EntityMarketingReportResponse report) {
        String json;
        try {
            json = objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            log.error("Failed to serialize marketing report for entity {} - not caching this result", id, e);
            return;
        }
        EntityMarketingReportCache row = cacheRepository
                .findByEntityIdAndPeriodAndWindowDays(id, period.name(), windowDays)
                .orElseGet(EntityMarketingReportCache::new);
        row.setEntityId(id);
        row.setPeriod(period.name());
        row.setWindowDays(windowDays);
        row.setReportJson(json);
        row.setGeneratedAt(report.getGeneratedAt());
        cacheRepository.save(row);
    }

    private EntityMarketingReportResponse deserialize(EntityMarketingReportCache row, Long id) {
        try {
            return objectMapper.readValue(row.getReportJson(), EntityMarketingReportResponse.class);
        } catch (Exception e) {
            log.error("Failed to deserialize cached marketing report for entity {} - regenerating live", id, e);
            return null;
        }
    }

    public EntityMarketingReportResponse generateReport(String entityType, Long id,
                                                        TimePeriod period, int windowDays) {
        // Mandatory and owner-scoped: 404s if the entity is absent or not owned by the caller,
        // 400s on a type mismatch — this is what enforces ownership for the whole report.
        EntityDetailResponse entity = entityService.getEntityById(entityType, id);
        return assemble(id, entity, period, windowDays, () -> momentumCausalReportService.buildReport(id));
    }

    /**
     * Same assembly as {@link #generateReport}, but for the scheduled cache refresh: the entity has
     * already been resolved directly from {@link ManagedEntityRepository} (no owner-scoped lookup,
     * since there's no authenticated request to scope it against), and the momentum/causal-chain
     * section is fetched via {@link MomentumCausalReportService#buildReportForEntity} for the same
     * reason.
     */
    private EntityMarketingReportResponse assembleForBatch(ManagedEntity managedEntity,
                                                           TimePeriod period, int windowDays) {
        EntityDetailResponse entity = entityService.mapToDetailResponse(managedEntity);
        return assemble(managedEntity.getId(), entity, period, windowDays,
                () -> momentumCausalReportService.buildReportForEntity(managedEntity));
    }

    private EntityMarketingReportResponse assemble(Long id, EntityDetailResponse entity,
                                                    TimePeriod period, int windowDays,
                                                    Supplier<MomentumCausalReportResponse> momentumSupplier) {
        // Mandatory: the headline numbers the whole report is built around.
        EntityStatsResponse stats = dashboardService.getEntityStats(id);
        EntityStatsAvgResponse avg = dashboardService.getEntityStatsAvg(id);

        Map<String, Map<String, Long>> platformReach = optional("platform-reach", id,
                () -> dashboardService.getPlatformMentions(id));

        HeadlineMetrics headline = HeadlineMetrics.builder()
                .totalMentions(stats.getTotalMentions())
                .overallSentiment(stats.getOverallSentiment())
                .positivityRatio(avg.getPositiveRatio())
                .positiveSentiment(stats.getPositiveSentiment())
                .negativeSentiment(stats.getNegativeSentiment())
                .neutralSentiment(stats.getNeutralSentiment())
                .netSentimentScore(stats.getNetSentimentScore())
                .platformsCovered(platformReach == null ? 0 : platformReach.size())
                .build();

        CompetitivePositioning positioning = optional("competitive-positioning", id,
                () -> buildPositioning(entity.getName(), dashboardService.getCompetitorSnapshot(id)));

        SentimentOverTimeResponse trend = optional("sentiment-trend", id,
                () -> dashboardService.getSentimentOverTime(period, List.of(id)));

        var definingMoments = optional("defining-moments", id,
                () -> dashboardService.getCheckpointImpact(id, windowDays));
        var checkpointTrend = optional("checkpoint-trend", id,
                () -> dashboardService.getCheckpointTrend(id));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var sentimentDelta = optional("sentiment-delta", id,
                () -> dashboardService.getSentimentDelta(id, today.minusDays(windowDays), today, windowDays));

        var movieHealth = optional("movie-health", id, () -> dashboardService.getMovieHealth(id));
        var buzz = optional("buzz", id, () -> dashboardService.getBuzz(id));
        var reach = optional("reach", id, () -> dashboardService.getReachDirect(id));
        var awareness = optional("awareness", id, () -> dashboardService.getAwareness(id));

        var audiencePulse = optional("audience-pulse", id, () -> dashboardService.getAudiencePulse(id));
        var audiencePulseAspects = optional("audience-pulse-aspects", id,
                () -> audiencePulseAspectsService.getAspects(id, false));

        var promotionalMix = optional("promotional-mix", id, () -> dashboardService.getPromotionalMix(id));
        var authorTypeBreakdown = optional("author-type-breakdown", id,
                () -> dashboardService.getAuthorTypeBreakdown(id));
        var contentIntentBreakdown = optional("content-intent-breakdown", id,
                () -> dashboardService.getContentIntentBreakdown(id));
        var topicCategoryBreakdown = optional("topic-category-breakdown", id,
                () -> dashboardService.getTopicCategoryBreakdown(id));

        var hourlyActivity = optional("hourly-activity", id,
                () -> dashboardService.getHourlyActivity(id, period, null, null, null));

        var topSpreaders = optional("top-spreaders", id, () -> topSpreaderContentService.getTopSpreaderContent(
                id, null, TOP_SPREADER_LIMIT, TOP_SPREADER_POSTS_PER_SPREADER));
        var topSpreaderInsights = optional("top-spreader-insights", id, () -> topSpreaderInsightsService.getInsights(
                id, null, TOP_SPREADER_LIMIT, TOP_SPREADER_POSTS_PER_SPREADER, false));

        // The report wants the full accumulated plan (every status, no 5-action cap), not the
        // "what to do" panel's capped/randomly-sampled ACTIVE subset - see
        // RecommendedActionsService.getAllRecommendedActions's own doc for that distinction.
        var recommendedActions = optional("recommended-actions", id,
                () -> recommendedActionsService.getAllRecommendedActions(id, null));

        var aiSummary = optional("ai-summary", id, () -> commandCenterSummaryService.getAiSummary(id, false));
        var todaysHighlights = optional("todays-highlights", id,
                () -> commandCenterSummaryService.getTodaysHighlights(id, false));

        var momentumIntelligence = optional("momentum-intelligence", id, momentumSupplier);

        AuraMathResult auraMath = fetchAuraMathReport(id);

        List<String> highlights = buildHighlights(entity, headline, positioning, platformReach);

        return EntityMarketingReportResponse.builder()
                .generatedAt(Instant.now())
                .period(period.name())
                .entity(entity)
                .headlineMetrics(headline)
                .competitivePositioning(positioning)
                .sentimentTrend(trend)
                .platformReach(platformReach)
                .definingMoments(definingMoments)
                .checkpointTrend(checkpointTrend)
                .sentimentDelta(sentimentDelta)
                .movieHealth(movieHealth)
                .buzz(buzz)
                .reach(reach)
                .awareness(awareness)
                .audiencePulse(audiencePulse)
                .audiencePulseAspects(audiencePulseAspects)
                .promotionalMix(promotionalMix)
                .authorTypeBreakdown(authorTypeBreakdown)
                .contentIntentBreakdown(contentIntentBreakdown)
                .topicCategoryBreakdown(topicCategoryBreakdown)
                .hourlyActivity(hourlyActivity)
                .topSpreaders(topSpreaders)
                .topSpreaderInsights(topSpreaderInsights)
                .recommendedActions(recommendedActions)
                .aiSummary(aiSummary)
                .todaysHighlights(todaysHighlights)
                .momentumIntelligence(momentumIntelligence)
                .auraMathIntelligence(auraMath.body())
                .auraMathStatus(auraMath.status())
                .highlights(highlights)
                .build();
    }

    // ------------------------------------------------------------------
    // Competitive positioning
    // ------------------------------------------------------------------

    private CompetitivePositioning buildPositioning(String entityName, List<CompetitorSnapshot> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        // Rank by net sentiment (highest first); the snapshot already contains the entity + competitors.
        List<CompetitorSnapshot> ranked = new ArrayList<>(snapshot);
        ranked.sort(Comparator.comparingDouble(CompetitorSnapshot::getNetSentimentScore).reversed());

        int rank = 1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getEntityName().equals(entityName)) {
                rank = i + 1;
                break;
            }
        }
        String leaderName = ranked.get(0).getEntityName();

        return CompetitivePositioning.builder()
                .snapshot(snapshot)
                .totalTracked(snapshot.size())
                .rank(rank)
                .leadsCategory(leaderName.equals(entityName))
                .leaderName(leaderName)
                .build();
    }

    // ------------------------------------------------------------------
    // AuraMath upstream report
    // ------------------------------------------------------------------

    private AuraMathResult fetchAuraMathReport(Long id) {
        String entityId = String.valueOf(id);
        try {
            ResponseEntity<String> upstream = auraMathProxy.forwardEntityReport(
                    WRAPPER_PATH,
                    "/api/marketing/entity-report/" + encodeSegment(entityId),
                    entityId);

            if (upstream.getStatusCode().is2xxSuccessful()) {
                String body = upstream.getBody();
                if (body != null && !body.isBlank()) {
                    return new AuraMathResult(objectMapper.readTree(body), "ok");
                }
            } else {
                log.info("entity-report auramath unavailable id={} status={}",
                        id, upstream.getStatusCode().value());
            }
        } catch (Exception e) {
            log.warn("entity-report auramath fetch failed id={}", id, e);
        }
        return new AuraMathResult(null, "unavailable");
    }

    private record AuraMathResult(JsonNode body, String status) {
    }

    // ------------------------------------------------------------------
    // Deterministic highlights
    // ------------------------------------------------------------------

    private List<String> buildHighlights(EntityDetailResponse entity,
                                         HeadlineMetrics metrics,
                                         CompetitivePositioning positioning,
                                         Map<String, Map<String, Long>> platformReach) {
        List<String> highlights = new ArrayList<>();

        if (metrics.getTotalMentions() > 0) {
            highlights.add(String.format(Locale.US,
                    "%s analysed across %d platform%s of audience conversation",
                    formatCount(metrics.getTotalMentions()),
                    metrics.getPlatformsCovered(),
                    metrics.getPlatformsCovered() == 1 ? "" : "s"));

            highlights.add(String.format(Locale.US,
                    "%.0f%% of all mentions are positive",
                    metrics.getPositivityRatio() * 100));
        }

        if (metrics.getNetSentimentScore() >= 1.0) {
            highlights.add(String.format(Locale.US,
                    "%.1f positive mentions for every negative one",
                    metrics.getNetSentimentScore()));
        }

        if (positioning != null && positioning.getTotalTracked() > 1) {
            if (positioning.isLeadsCategory()) {
                highlights.add(String.format(Locale.US,
                        "Leads its category — #1 of %d tracked titles on net sentiment",
                        positioning.getTotalTracked()));
            } else {
                highlights.add(String.format(Locale.US,
                        "Ranks #%d of %d tracked titles on net sentiment",
                        positioning.getRank(), positioning.getTotalTracked()));
            }
        }

        String topPlatform = topPlatform(platformReach);
        if (topPlatform != null) {
            highlights.add("Strongest reach on " + topPlatform);
        }

        if (entity.getReleaseDate() != null) {
            highlights.add("Tracking sentiment around the " + entity.getReleaseDate() + " release");
        }

        return highlights;
    }

    /** The platform carrying the most total mentions, or {@code null} if none. */
    private String topPlatform(Map<String, Map<String, Long>> platformReach) {
        if (platformReach == null || platformReach.isEmpty()) {
            return null;
        }
        String top = null;
        long best = -1;
        for (Map.Entry<String, Map<String, Long>> e : platformReach.entrySet()) {
            long total = e.getValue() == null ? 0
                    : e.getValue().values().stream().filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
            if (total > best) {
                best = total;
                top = e.getKey();
            }
        }
        return best > 0 ? top : null;
    }

    private static String formatCount(long count) {
        if (count >= 1_000_000) {
            return String.format(Locale.US, "%.1fM mentions", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.US, "%.1fK mentions", count / 1_000.0);
        }
        return count + (count == 1 ? " mention" : " mentions");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Run an optional report section, returning {@code null} (and logging) instead of propagating if it
     * fails — keeps the prospect-facing report resilient to a single flaky source.
     */
    private <T> T optional(String section, Long id, java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("entity-report section '{}' unavailable id={}", section, id, e);
            return null;
        }
    }

    private static String encodeSegment(String segment) {
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
