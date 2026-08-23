package com.aura.service.service;

import com.aura.service.dto.MomentumCausalReportResponse;
import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.proxy.AuraMathClientConfig;
import com.aura.service.proxy.AuraMathProperties;
import com.aura.service.proxy.AuraMathProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Drives {@link MomentumCausalReportService} against a real {@link AuraMathProxyService} backed by a
 * {@link MockWebServer} (same style as {@code EntityCausalIntelControllerTest}), with only the true
 * interface dependencies ({@link EntityAccessService}, {@link RecommendedActionCandidateService})
 * mocked - this project's Java setup breaks Mockito's inline mocking of concrete classes (see project
 * memory), and both of these are already defined as interfaces for exactly this reason.
 */
class MomentumCausalReportServiceTest {

    private static final Long ENTITY_ID = 42L;

    private MockWebServer upstream;
    private EntityAccessService entityAccessService;
    private RecommendedActionCandidateService candidateService;
    private MomentumCausalReportService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        upstream = new MockWebServer();
        upstream.start();

        AuraMathProperties props = new AuraMathProperties();
        props.setBaseUrl(upstream.url("/").toString().replaceAll("/$", ""));
        props.setConnectTimeoutMs(2_000);
        props.setReadTimeoutMs(5_000);
        props.setSyncReadTimeoutMs(5_000);

        AuraMathClientConfig config = new AuraMathClientConfig();
        var provider = config.auraMathConnectionProvider();
        AuraMathProxyService proxyService = new AuraMathProxyService(
                config.auraMathWebClient(props, provider),
                config.auraMathSyncWebClient(props, provider),
                props,
                mapper
        );

        entityAccessService = mock(EntityAccessService.class);
        candidateService = mock(RecommendedActionCandidateService.class);
        service = new MomentumCausalReportService(entityAccessService, proxyService, props, candidateService, mapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.shutdown();
    }

    private void enqueueJson(String json) {
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = upstream.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        return req;
    }

    private static ManagedEntity ownedEntity(String language, String name) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        entity.setName(name);
        entity.setType("MOVIE");
        entity.setLanguage(language);
        entity.setIndustry("Sandalwood");
        return entity;
    }

    // ------------------------------------------------------------------
    // Ownership is enforced before any upstream call is made
    // ------------------------------------------------------------------

    @Test
    void buildReport_notOwned_throwsAndNeverCallsUpstreamOrCandidateService() {
        when(entityAccessService.assertOwnedByCurrentUser(ENTITY_ID))
                .thenThrow(new ResourceNotFoundException("not found"));

        assertThatThrownBy(() -> service.buildReport(ENTITY_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(upstream.getRequestCount()).isEqualTo(0);
        verifyNoInteractions(candidateService);
    }

    /**
     * {@code buildReportForEntity} exists for the scheduled cache refresh in
     * {@code EntityMarketingReportService}, which has no authenticated request to check ownership
     * against - it must assemble the same report shape without ever touching
     * {@link EntityAccessService}.
     */
    @Test
    void buildReportForEntity_skipsOwnershipCheck_butProducesSameShapeReport() {
        ManagedEntity entity = ownedEntity("Kannada", "Test Movie");

        enqueueJson("{\"status\":\"insufficient_history\",\"details\":\"no vmi\"}");
        enqueueJson("{\"status\":\"insufficient_history\",\"details\":\"no chains\"}");
        enqueueJson("{\"language\":\"Kannada\",\"movie\":\"Test Movie\",\"totalUsers\":0,\"users\":[]}");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of());

        MomentumCausalReportResponse report = service.buildReportForEntity(entity);

        assertThat(report.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(report.getEntityName()).isEqualTo("Test Movie");
        verifyNoInteractions(entityAccessService);
    }

    // ------------------------------------------------------------------
    // Fully-populated entity: all four sections come back "ok" with real data intact.
    // ------------------------------------------------------------------

    @Test
    void buildReport_fullyPopulatedEntity_producesAllFourSections() throws Exception {
        when(entityAccessService.assertOwnedByCurrentUser(ENTITY_ID))
                .thenReturn(ownedEntity("Kannada", "Test Movie"));

        enqueueJson("{\"status\":\"ok\",\"entityId\":42,\"series\":[{\"day_index\":1,"
                + "\"daily_engagement_volume\":100}],\"peakDay\":{\"dayIndex\":1,\"calendarDate\":\"2026-01-01\"}}");
        enqueueJson("{\"status\":\"ok\",\"entityId\":42,\"chains\":[{\"pathScore\":0.9,\"edges\":["
                + "{\"from_series\":\"vmi\",\"to_series\":\"sentiment\",\"lag\":2,\"fdr_q_value\":0.01,"
                + "\"effect_size_r2\":0.55,\"n_entities_supporting\":12}]}]}");
        enqueueJson("{\"language\":\"Kannada\",\"movie\":\"Test Movie\",\"totalUsers\":3,\"users\":["
                + "{\"global_user_id\":\"u1\",\"mention_count\":5,\"engagement_rating\":0.8,"
                + "\"causal_lift_score\":1.2,\"n_qualifying_events\":3,\"confidence\":\"HIGH\"},"
                + "{\"global_user_id\":\"u2\",\"mention_count\":2,\"engagement_rating\":0.5,"
                + "\"causal_lift_score\":2.5,\"n_qualifying_events\":1,\"confidence\":\"LOW\"},"
                + "{\"global_user_id\":\"u3\",\"mention_count\":1,\"engagement_rating\":0.1}]}");

        RecommendedActionCandidate lever = new RecommendedActionCandidate(
                "nonobvious-lever-foo", "Foo Lever", RecommendedActionCategory.MEDIUM_IMPACT, 85,
                -120, -1, "some window", List.of(), List.of(), List.of(),
                new RecommendedActionCandidate.StatisticalEvidence("foo", "positive", 0.001, 0.02, 50L,
                        null, null, null));
        RecommendedActionCandidate playbook = new RecommendedActionCandidate(
                "playbook-sequence-sandalwood-kannada", "Sandalwood / Kannada playbook sequence",
                RecommendedActionCategory.MEDIUM_IMPACT, 70, -120, -1, "some window",
                List.of(), List.of(), List.of(),
                new RecommendedActionCandidate.StatisticalEvidence(null, null, null, 0.04, 30L,
                        List.of("teaser", "trailer"), 40L, 10L));
        RecommendedActionCandidate unrelated = new RecommendedActionCandidate(
                "factor-46-trailer-teaser-timing", "Teaser/Trailer Timing", RecommendedActionCategory.HIGH_IMPACT,
                90, -45, -30, "some window", List.of("fact"), List.of(), List.of());
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(lever, playbook, unrelated));

        MomentumCausalReportResponse report = service.buildReport(ENTITY_ID);

        assertThat(report.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(report.getEntityName()).isEqualTo("Test Movie");
        assertThat(report.getGeneratedAt()).isNotNull();

        assertThat(report.getVmiTrend().get("status").asText()).isEqualTo("ok");
        assertThat(report.getVmiTrend().get("peakDay").get("dayIndex").asInt()).isEqualTo(1);
        assertThat(report.getVmiTrend().get("series").get(0).get("daily_engagement_volume").asInt()).isEqualTo(100);

        assertThat(report.getCausalChains().get("status").asText()).isEqualTo("ok");
        var edge = report.getCausalChains().get("chains").get(0).get("edges").get(0);
        assertThat(edge.get("lag").asInt()).isEqualTo(2);
        assertThat(edge.get("fdr_q_value").asDouble()).isEqualTo(0.01);
        assertThat(edge.get("effect_size_r2").asDouble()).isEqualTo(0.55);
        assertThat(edge.get("n_entities_supporting").asInt()).isEqualTo(12);

        var users = report.getTopCausalLiftUsers();
        assertThat(users.getStatus()).isEqualTo("ok");
        // u3 (null causal_lift_score) is dropped; HIGH (u1) sorts ahead of LOW (u2) despite the
        // lower score.
        assertThat(users.getUsers()).extracting(MomentumCausalReportResponse.CausalLiftUser::getGlobalUserId)
                .containsExactly("u1", "u2");
        assertThat(users.getUsers().get(0).getConfidence()).isEqualTo("HIGH");
        assertThat(users.getUsers().get(1).getConfidence()).isEqualTo("LOW");

        assertThat(report.getNonObviousLevers().getStatus()).isEqualTo("ok");
        assertThat(report.getNonObviousLevers().getCandidates()).containsExactly(lever);

        assertThat(report.getPlaybookMatches().getStatus()).isEqualTo("ok");
        assertThat(report.getPlaybookMatches().getCandidates()).containsExactly(playbook);

        RecordedRequest vmiReq = takeRequest();
        assertThat(vmiReq.getPath()).contains("/api/marketing/entity/42/vmi");
        RecordedRequest chainsReq = takeRequest();
        assertThat(chainsReq.getPath()).contains("/api/marketing/entity/42/causal-chains");
        RecordedRequest usersReq = takeRequest();
        assertThat(usersReq.getPath()).contains("/api/marketing/language/Kannada/movie/Test%20Movie/users");
    }

    // ------------------------------------------------------------------
    // Freshly-tracked entity: every section renders an explicit insufficient-history placeholder,
    // never an empty section, a 500, or an error propagated to the caller.
    // ------------------------------------------------------------------

    @Test
    void buildReport_freshlyTrackedEntity_allSectionsRenderInsufficientHistory() throws Exception {
        when(entityAccessService.assertOwnedByCurrentUser(ENTITY_ID))
                .thenReturn(ownedEntity("Kannada", "Brand New Movie"));

        enqueueJson("{\"status\":\"insufficient_history\",\"details\":\"VMI computation hasn't run yet.\"}");
        enqueueJson("{\"status\":\"insufficient_history\",\"details\":\"No qualifying cohort history.\"}");
        enqueueJson("{\"language\":\"Kannada\",\"movie\":\"Brand New Movie\",\"totalUsers\":1,\"users\":["
                + "{\"global_user_id\":\"u1\",\"mention_count\":1}]}");

        RecommendedActionCandidate unrelated = new RecommendedActionCandidate(
                "factor-46-trailer-teaser-timing", "Teaser/Trailer Timing", RecommendedActionCategory.HIGH_IMPACT,
                90, -45, -30, "some window", List.of("fact"), List.of(), List.of());
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of(unrelated));

        MomentumCausalReportResponse report = service.buildReport(ENTITY_ID);

        assertThat(report.getVmiTrend().get("status").asText()).isEqualTo("insufficient_history");
        assertThat(report.getVmiTrend().get("details").asText()).isEqualTo("VMI computation hasn't run yet.");

        assertThat(report.getCausalChains().get("status").asText()).isEqualTo("insufficient_history");
        assertThat(report.getCausalChains().get("details").asText()).isEqualTo("No qualifying cohort history.");

        assertThat(report.getTopCausalLiftUsers().getStatus()).isEqualTo("insufficient_history");
        assertThat(report.getTopCausalLiftUsers().getDetails()).isNotBlank();
        assertThat(report.getTopCausalLiftUsers().getUsers()).isEmpty();

        assertThat(report.getNonObviousLevers().getStatus()).isEqualTo("insufficient_history");
        assertThat(report.getNonObviousLevers().getDetails()).isNotBlank();
        assertThat(report.getNonObviousLevers().getCandidates()).isEmpty();

        assertThat(report.getPlaybookMatches().getStatus()).isEqualTo("insufficient_history");
        assertThat(report.getPlaybookMatches().getDetails()).isNotBlank();
        assertThat(report.getPlaybookMatches().getCandidates()).isEmpty();
    }

    @Test
    void buildReport_entityWithNoLanguageOrName_causalLiftUsersInsufficientWithoutUpstreamCall() throws Exception {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        entity.setName(null);
        entity.setType("MOVIE");
        when(entityAccessService.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity);

        enqueueJson("{\"status\":\"insufficient_history\",\"details\":\"none\"}");
        enqueueJson("{\"status\":\"insufficient_history\",\"details\":\"none\"}");
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenReturn(List.of());

        MomentumCausalReportResponse report = service.buildReport(ENTITY_ID);

        assertThat(report.getTopCausalLiftUsers().getStatus()).isEqualTo("insufficient_history");
        // Only vmi + causal-chains hit the upstream - the causal-lift lookup never fired because
        // there's no language/name to resolve a cohort with.
        assertThat(upstream.getRequestCount()).isEqualTo(2);
    }

    @Test
    void buildReport_upstreamUnavailable_degradesToInsufficientHistoryInsteadOfPropagatingError() throws Exception {
        when(entityAccessService.assertOwnedByCurrentUser(ENTITY_ID))
                .thenReturn(ownedEntity("Kannada", "Test Movie"));

        upstream.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        upstream.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        upstream.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        when(candidateService.buildCandidateActions(ENTITY_ID)).thenThrow(new RuntimeException("db down"));

        MomentumCausalReportResponse report = service.buildReport(ENTITY_ID);

        assertThat(report.getVmiTrend().get("status").asText()).isEqualTo("insufficient_history");
        assertThat(report.getCausalChains().get("status").asText()).isEqualTo("insufficient_history");
        assertThat(report.getTopCausalLiftUsers().getStatus()).isEqualTo("insufficient_history");
        assertThat(report.getNonObviousLevers().getStatus()).isEqualTo("insufficient_history");
        assertThat(report.getPlaybookMatches().getStatus()).isEqualTo("insufficient_history");
    }
}
