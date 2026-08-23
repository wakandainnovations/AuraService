package com.aura.service.service;

import com.aura.service.dto.AiSummaryResponse;
import com.aura.service.dto.AudiencePulseAspectsResponse;
import com.aura.service.dto.CompetitorSnapshot;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityStatsAvgResponse;
import com.aura.service.dto.EntityStatsResponse;
import com.aura.service.dto.MomentumCausalReportResponse;
import com.aura.service.dto.RecommendedActionsResponse;
import com.aura.service.enums.RecommendedActionStatus;
import com.aura.service.dto.SentimentOverTimeResponse;
import com.aura.service.dto.TodaysHighlightsResponse;
import com.aura.service.dto.TopSpreaderContentResponse;
import com.aura.service.dto.TopSpreaderInsightsResponse;
import com.aura.service.entity.EntityMarketingReportCache;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.TimePeriod;
import com.aura.service.proxy.AuraMathProperties;
import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.repository.EntityMarketingReportCacheRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EntityMarketingReportService}: assembly of the headline metrics, competitive
 * positioning, deterministic highlights, and graceful degradation when AuraMath / optional sections
 * fail. Uses hand-written stubs for the concrete collaborators — the JDK in use breaks Mockito's
 * inline mocking of concrete classes (see project memory).
 */
class EntityMarketingReportServiceTest {

    private static final Long ENTITY_ID = 42L;
    private static final String TYPE = "MOVIE";

    private StubEntityService entityService;
    private StubDashboardService dashboardService;
    private StubAuraMathProxy auraMathProxy;
    private EntityMarketingReportCacheRepository cacheRepository;
    private ManagedEntityRepository managedEntityRepository;
    private EntityMarketingReportService service;

    @BeforeEach
    void setUp() {
        entityService = new StubEntityService();
        dashboardService = new StubDashboardService();
        auraMathProxy = new StubAuraMathProxy();
        // Instant fields on the report (generatedAt) need the JSR-310 module registered to round-trip
        // through the cache's serialize/deserialize path exercised by the getReport(...) tests below.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        cacheRepository = mock(EntityMarketingReportCacheRepository.class);
        when(cacheRepository.findByEntityIdAndPeriodAndWindowDays(anyLong(), anyString(), anyInt()))
                .thenReturn(Optional.empty());
        managedEntityRepository = mock(ManagedEntityRepository.class);
        service = new EntityMarketingReportService(
                entityService, dashboardService, auraMathProxy, objectMapper,
                new StubMomentumCausalReportService(), new StubCommandCenterSummaryService(),
                new StubTopSpreaderContentService(), new StubTopSpreaderInsightsService(),
                new StubRecommendedActionsService(), new StubAudiencePulseAspectsService(),
                cacheRepository, managedEntityRepository);

        EntityDetailResponse entity = new EntityDetailResponse();
        entity.setId(ENTITY_ID);
        entity.setName("Vikram");
        entity.setType(TYPE);
        entity.setReleaseDate(LocalDate.of(2026, 7, 1));
        entityService.entity = entity;

        // 8000 mentions, 70% positive, net 5.0 positive:negative
        dashboardService.stats = new EntityStatsResponse(8000L, 0.70, 0.14, 0.16, 5.0, 0.62);
        dashboardService.avg = new EntityStatsAvgResponse(8000L, 0.62, 0.70, 5.0);
        dashboardService.platformMentions = Map.of(
                "TWITTER", Map.of("POSITIVE", 4000L, "NEGATIVE", 800L),
                "INSTAGRAM", Map.of("POSITIVE", 1600L, "NEGATIVE", 200L));
        dashboardService.competitorSnapshot = List.of(
                new CompetitorSnapshot("Vikram", 8000L, 0.62, 0.70, 5.0),
                new CompetitorSnapshot("RivalFilm", 5000L, 0.40, 0.50, 2.0));
        dashboardService.sentiment = new SentimentOverTimeResponse(List.of());
    }

    @Test
    void assemblesAllSections_andEmbedsAuraMath() {
        auraMathProxy.response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"score\":91,\"verdict\":\"blockbuster\"}");

        EntityMarketingReportResponse report = service.generateReport(TYPE, ENTITY_ID, TimePeriod.DAY30, 7);

        assertThat(report.getEntity().getName()).isEqualTo("Vikram");
        assertThat(report.getPeriod()).isEqualTo("DAY30");
        assertThat(report.getGeneratedAt()).isNotNull();

        assertThat(report.getHeadlineMetrics().getTotalMentions()).isEqualTo(8000L);
        assertThat(report.getHeadlineMetrics().getPositivityRatio()).isEqualTo(0.70);
        assertThat(report.getHeadlineMetrics().getNetSentimentScore()).isEqualTo(5.0);
        assertThat(report.getHeadlineMetrics().getPlatformsCovered()).isEqualTo(2);

        assertThat(report.getAuraMathStatus()).isEqualTo("ok");
        assertThat(report.getAuraMathIntelligence().get("verdict").asText()).isEqualTo("blockbuster");
    }

    @Test
    void ranksTopCompetitor_andFlagsCategoryLeader() {
        auraMathProxy.response = ResponseEntity.ok().body("{}");

        EntityMarketingReportResponse report = service.generateReport(TYPE, ENTITY_ID, TimePeriod.DAY30, 7);

        var pos = report.getCompetitivePositioning();
        assertThat(pos.getTotalTracked()).isEqualTo(2);
        assertThat(pos.getRank()).isEqualTo(1);
        assertThat(pos.isLeadsCategory()).isTrue();
        assertThat(pos.getLeaderName()).isEqualTo("Vikram");

        assertThat(report.getHighlights())
                .anySatisfy(h -> assertThat(h).contains("Leads its category"))
                .anySatisfy(h -> assertThat(h).contains("8.0K mentions"))
                .anySatisfy(h -> assertThat(h).contains("70% of all mentions are positive"))
                .anySatisfy(h -> assertThat(h).contains("Strongest reach on TWITTER"));
    }

    @Test
    void rankBehindCompetitor_isReportedNotAsLeader() {
        // Rival now out-performs on net sentiment.
        dashboardService.competitorSnapshot = List.of(
                new CompetitorSnapshot("Vikram", 8000L, 0.62, 0.70, 2.0),
                new CompetitorSnapshot("RivalFilm", 5000L, 0.40, 0.50, 9.0));
        auraMathProxy.response = ResponseEntity.ok().body("{}");

        var pos = service.generateReport(TYPE, ENTITY_ID, TimePeriod.DAY30, 7).getCompetitivePositioning();

        assertThat(pos.getRank()).isEqualTo(2);
        assertThat(pos.isLeadsCategory()).isFalse();
        assertThat(pos.getLeaderName()).isEqualTo("RivalFilm");
    }

    @Test
    void degradesGracefully_whenAuraMathUnavailable() {
        auraMathProxy.response = ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("{\"error\":\"upstream_failure\"}");

        EntityMarketingReportResponse report = service.generateReport(TYPE, ENTITY_ID, TimePeriod.DAY30, 7);

        assertThat(report.getAuraMathStatus()).isEqualTo("unavailable");
        assertThat(report.getAuraMathIntelligence()).isNull();
        // Core report is still fully populated.
        assertThat(report.getHeadlineMetrics().getTotalMentions()).isEqualTo(8000L);
        assertThat(report.getHighlights()).isNotEmpty();
    }

    @Test
    void degradesGracefully_whenOptionalSectionThrows() {
        dashboardService.throwOnPlatform = true;
        auraMathProxy.response = ResponseEntity.ok().body("{}");

        EntityMarketingReportResponse report = service.generateReport(TYPE, ENTITY_ID, TimePeriod.DAY30, 7);

        assertThat(report.getPlatformReach()).isNull();
        assertThat(report.getHeadlineMetrics().getPlatformsCovered()).isZero();
        assertThat(report.getEntity().getName()).isEqualTo("Vikram");
    }

    @Test
    void propagates_whenEntityMissing() {
        entityService.throwNotFound = true;

        assertThatThrownBy(() -> service.generateReport(TYPE, ENTITY_ID, TimePeriod.DAY30, 7))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Entity not found");
    }

    // ------------------------------------------------------------------
    // getReport(...) caching
    // ------------------------------------------------------------------

    @Test
    void getReport_cacheHit_returnsCachedReport_withoutRegenerating() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EntityMarketingReportResponse cached = EntityMarketingReportResponse.builder()
                .period("CACHED-MARKER")
                .build();
        EntityMarketingReportCache row = new EntityMarketingReportCache();
        row.setEntityId(ENTITY_ID);
        row.setPeriod(TimePeriod.DAY30.name());
        row.setWindowDays(7);
        row.setReportJson(mapper.writeValueAsString(cached));
        row.setGeneratedAt(java.time.Instant.now());
        when(cacheRepository.findByEntityIdAndPeriodAndWindowDays(ENTITY_ID, "DAY30", 7))
                .thenReturn(Optional.of(row));

        EntityMarketingReportResponse result = service.getReport(TYPE, ENTITY_ID, TimePeriod.DAY30, 7, false);

        assertThat(result.getPeriod()).isEqualTo("CACHED-MARKER");
        // Ownership is still checked, but nothing else is regenerated from the cached row.
        assertThat(dashboardService.getEntityStatsCalls).isZero();
        verify(cacheRepository, never()).save(any());
    }

    @Test
    void getReport_cacheMiss_generatesAndPersists() {
        auraMathProxy.response = ResponseEntity.ok().body("{}");
        when(cacheRepository.findByEntityIdAndPeriodAndWindowDays(ENTITY_ID, "DAY30", 7))
                .thenReturn(Optional.empty());

        EntityMarketingReportResponse result = service.getReport(TYPE, ENTITY_ID, TimePeriod.DAY30, 7, false);

        assertThat(result.getHeadlineMetrics().getTotalMentions()).isEqualTo(8000L);
        assertThat(dashboardService.getEntityStatsCalls).isEqualTo(1);
        verify(cacheRepository).save(any(EntityMarketingReportCache.class));
    }

    @Test
    void getReport_refreshTrue_bypassesCache_andRegenerates() {
        auraMathProxy.response = ResponseEntity.ok().body("{}");
        EntityMarketingReportCache staleRow = new EntityMarketingReportCache();
        staleRow.setReportJson("{\"period\":\"STALE\"}");
        when(cacheRepository.findByEntityIdAndPeriodAndWindowDays(ENTITY_ID, "DAY30", 7))
                .thenReturn(Optional.of(staleRow));

        EntityMarketingReportResponse result = service.getReport(TYPE, ENTITY_ID, TimePeriod.DAY30, 7, true);

        assertThat(result.getPeriod()).isEqualTo("DAY30");
        assertThat(dashboardService.getEntityStatsCalls).isEqualTo(1);
        verify(cacheRepository).save(any(EntityMarketingReportCache.class));
    }

    @Test
    void refreshAllReports_iteratesEntities_andPersistsEachOne() {
        ManagedEntity movie = new ManagedEntity();
        movie.setId(99L);
        movie.setName("Some Movie");
        movie.setType("MOVIE");
        when(managedEntityRepository.findAll()).thenReturn(List.of(movie));
        auraMathProxy.response = ResponseEntity.ok().body("{}");

        service.refreshAllReports();

        verify(cacheRepository).save(any(EntityMarketingReportCache.class));
    }

    // ------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------

    static class StubEntityService extends EntityService {
        EntityDetailResponse entity;
        boolean throwNotFound;

        StubEntityService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public EntityDetailResponse getEntityById(String entityType, Long id) {
            if (throwNotFound) {
                throw new RuntimeException("Entity not found with id: " + id);
            }
            return entity;
        }
    }

    static class StubDashboardService extends DashboardService {
        EntityStatsResponse stats;
        EntityStatsAvgResponse avg;
        Map<String, Map<String, Long>> platformMentions;
        List<CompetitorSnapshot> competitorSnapshot;
        SentimentOverTimeResponse sentiment;
        boolean throwOnPlatform;
        int getEntityStatsCalls;

        StubDashboardService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public EntityStatsResponse getEntityStats(Long entityId) {
            getEntityStatsCalls++;
            return stats;
        }

        @Override
        public EntityStatsAvgResponse getEntityStatsAvg(Long entityId) {
            return avg;
        }

        @Override
        public Map<String, Map<String, Long>> getPlatformMentions(Long entityId) {
            if (throwOnPlatform) {
                throw new RuntimeException("boom");
            }
            return platformMentions;
        }

        @Override
        public List<CompetitorSnapshot> getCompetitorSnapshot(Long entityId) {
            return competitorSnapshot;
        }

        @Override
        public SentimentOverTimeResponse getSentimentOverTime(TimePeriod period, List<Long> entityIds) {
            return sentiment;
        }

        @Override
        public com.aura.service.dto.CheckpointImpactResponse getCheckpointImpact(Long entityId, int windowDays) {
            return null;
        }

        @Override
        public com.aura.service.dto.CheckpointTrendResponse getCheckpointTrend(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.SentimentDeltaResponse getSentimentDelta(
                Long entityId, java.time.LocalDate fromDate, java.time.LocalDate toDate, int windowDays) {
            return null;
        }

        @Override
        public com.aura.service.dto.MovieHealthResponse getMovieHealth(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.BuzzResponse getBuzz(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.ReachResponse getReachDirect(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.AwarenessResponse getAwareness(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.AudiencePulseResponse getAudiencePulse(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.PromotionalMixResponse getPromotionalMix(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.AuthorTypeBreakdownResponse getAuthorTypeBreakdown(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.ContentIntentBreakdownResponse getContentIntentBreakdown(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.TopicCategoryBreakdownResponse getTopicCategoryBreakdown(Long entityId) {
            return null;
        }

        @Override
        public com.aura.service.dto.HourlyActivityResponse getHourlyActivity(
                Long entityId, TimePeriod period, String language, String industry, String state) {
            return null;
        }
    }

    static class StubMomentumCausalReportService extends MomentumCausalReportService {
        StubMomentumCausalReportService() {
            super(null, null, new AuraMathProperties(), null, new ObjectMapper());
        }

        @Override
        public MomentumCausalReportResponse buildReport(Long entityId) {
            return null;
        }

        @Override
        public MomentumCausalReportResponse buildReportForEntity(ManagedEntity entity) {
            return null;
        }
    }

    static class StubCommandCenterSummaryService extends CommandCenterSummaryService {
        StubCommandCenterSummaryService() {
            super(null, null, null, null, java.time.Clock.systemUTC());
        }

        @Override
        public AiSummaryResponse getAiSummary(Long entityId, boolean refresh) {
            return null;
        }

        @Override
        public TodaysHighlightsResponse getTodaysHighlights(Long entityId, boolean refresh) {
            return null;
        }
    }

    static class StubTopSpreaderContentService extends TopSpreaderContentService {
        StubTopSpreaderContentService() {
            super(null, null, new ObjectMapper());
        }

        @Override
        public TopSpreaderContentResponse getTopSpreaderContent(
                Long entityId, String language, int spreaderLimit, int postsPerSpreader) {
            return null;
        }
    }

    static class StubTopSpreaderInsightsService extends TopSpreaderInsightsService {
        StubTopSpreaderInsightsService() {
            super(null, null, null, null, new ObjectMapper(), java.time.Clock.systemUTC());
        }

        @Override
        public TopSpreaderInsightsResponse getInsights(
                Long entityId, String language, int spreaderLimit, int postsPerSpreader, boolean refresh) {
            return null;
        }
    }

    static class StubRecommendedActionsService extends RecommendedActionsService {
        StubRecommendedActionsService() {
            super(null, null, null, null, java.time.Clock.systemUTC(), null);
        }

        @Override
        public RecommendedActionsResponse getAllRecommendedActions(Long entityId, RecommendedActionStatus statusFilter) {
            return null;
        }
    }

    static class StubAudiencePulseAspectsService extends AudiencePulseAspectsService {
        StubAudiencePulseAspectsService() {
            super(null, null, null, new AuraMathProperties(), new ObjectMapper(), java.time.Clock.systemUTC());
        }

        @Override
        public AudiencePulseAspectsResponse getAspects(Long entityId, boolean refresh) {
            return null;
        }
    }

    static class StubAuraMathProxy extends AuraMathProxyService {
        ResponseEntity<String> response;

        StubAuraMathProxy() {
            super(null, null, new AuraMathProperties(), new ObjectMapper());
        }

        @Override
        public ResponseEntity<String> forwardEntityReport(String wrapperPath, String upstreamPath, String entityId) {
            return response;
        }
    }
}
