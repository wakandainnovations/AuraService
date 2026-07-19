package com.aura.service.service;

import com.aura.service.dto.CompetitorSnapshot;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityStatsAvgResponse;
import com.aura.service.dto.EntityStatsResponse;
import com.aura.service.dto.SentimentOverTimeResponse;
import com.aura.service.enums.TimePeriod;
import com.aura.service.proxy.AuraMathProperties;
import com.aura.service.proxy.AuraMathProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private EntityMarketingReportService service;

    @BeforeEach
    void setUp() {
        entityService = new StubEntityService();
        dashboardService = new StubDashboardService();
        auraMathProxy = new StubAuraMathProxy();
        service = new EntityMarketingReportService(
                entityService, dashboardService, auraMathProxy, new ObjectMapper());

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
    // Stubs
    // ------------------------------------------------------------------

    static class StubEntityService extends EntityService {
        EntityDetailResponse entity;
        boolean throwNotFound;

        StubEntityService() {
            super(null, null, null, null, null, null);
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

        StubDashboardService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public EntityStatsResponse getEntityStats(Long entityId) {
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
