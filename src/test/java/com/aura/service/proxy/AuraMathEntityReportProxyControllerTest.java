package com.aura.service.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the entity intelligence report wrappers:
 * full report (200), not-found 404 translation, no-history 200 pass-through,
 * upstream 5xx → 502, connection failure → 502, and entityId validation.
 */
class AuraMathEntityReportProxyControllerTest {

    private MockWebServer upstream;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
        mvc = buildMvc(upstream.url("/").toString().replaceAll("/$", ""));
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.shutdown();
    }

    private MockMvc buildMvc(String baseUrl) {
        AuraMathProperties props = new AuraMathProperties();
        props.setBaseUrl(baseUrl);
        props.setConnectTimeoutMs(1_000);
        props.setReadTimeoutMs(5_000);
        props.setSyncReadTimeoutMs(5_000);
        props.setMarketingTimeoutMs(5_000);

        AuraMathClientConfig config = new AuraMathClientConfig();
        var provider = config.auraMathConnectionProvider();
        AuraMathProxyService proxyService = new AuraMathProxyService(
                config.auraMathWebClient(props, provider),
                config.auraMathSyncWebClient(props, provider),
                props,
                mapper
        );
        AuraMathMarketingProxyController controller =
                new AuraMathMarketingProxyController(proxyService, props, mapper);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void enqueueJson(int code, String json) {
        upstream.enqueue(new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(json));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = upstream.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        return req;
    }

    // ==================================================================
    // A. Full report → 200, body forwarded unchanged
    // ==================================================================

    @Test
    void shareableReport_fullReport_returns200_andForwardsBody() throws Exception {
        String report = "{\"generatedAt\":\"2026-06-06T00:00:00Z\",\"entityProfile\":{\"name\":\"X\"},"
                + "\"topicIntelligence\":[],\"topAdvocates\":[],\"redFlags\":[],\"opportunityFlags\":[]}";
        enqueueJson(200, report);

        mvc.perform(get("/v1/marketing/entity-report/{id}", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityProfile.name").value("X"))
                .andExpect(jsonPath("$.topicIntelligence").isArray())
                // body must be forwarded byte-for-byte
                .andExpect(content().json(report));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/entity-report/42");
    }

    @Test
    void inAppReport_fullReport_returns200_hitsReportRoute() throws Exception {
        enqueueJson(200, "{\"generatedAt\":\"2026-06-06T00:00:00Z\",\"entityProfile\":{}}");

        mvc.perform(get("/v1/marketing/entity/{id}/report", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists());

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/entity/42/report");
    }

    // ==================================================================
    // B. Unknown entity (200 + "No entity found" message) → 404, body preserved
    // ==================================================================

    @Test
    void shareableReport_unknownEntity_isTranslatedTo404_preservingMessage() throws Exception {
        enqueueJson(200, "{\"entityId\":\"nope\",\"message\":\"No entity found for this id\"}");

        mvc.perform(get("/v1/marketing/entity-report/{id}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.entityId").value("nope"))
                .andExpect(jsonPath("$.message").value("No entity found for this id"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/entity-report/nope");
    }

    @Test
    void inAppReport_unknownEntity_isTranslatedTo404() throws Exception {
        enqueueJson(200, "{\"entityId\":\"nope\",\"message\":\"No entity found for this id\"}");

        mvc.perform(get("/v1/marketing/entity/{id}/report", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No entity found for this id"));
    }

    // ==================================================================
    // C. Entity exists but no scored posts → valid empty result, 200 pass-through
    // ==================================================================

    @Test
    void shareableReport_noScoredHistory_passesThroughAs200() throws Exception {
        String body = "{\"entityId\":\"7\",\"name\":\"Quiet Star\",\"trackedKeywords\":[\"x\"],"
                + "\"message\":\"No scored post history found for this entity yet\"}";
        enqueueJson(200, body);

        mvc.perform(get("/v1/marketing/entity-report/{id}", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Quiet Star"))
                .andExpect(jsonPath("$.message").value("No scored post history found for this entity yet"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/entity-report/7");
    }

    // ==================================================================
    // Upstream 5xx → 502 envelope {error, entityId, upstreamStatus}
    // ==================================================================

    @Test
    void upstream500_isMappedTo502_withEnvelope() throws Exception {
        enqueueJson(500, "{\"status\":500,\"message\":\"PSQLException: relation does not exist\"}");

        mvc.perform(get("/v1/marketing/entity-report/{id}", "42"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_failure"))
                .andExpect(jsonPath("$.entityId").value("42"))
                .andExpect(jsonPath("$.upstreamStatus").value(500))
                // upstream message/SQL fragments must not leak through
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    // ==================================================================
    // Connection failure → 502 with upstreamStatus: null
    // ==================================================================

    @Test
    void upstreamConnectionRefused_returns502_withNullUpstreamStatus() throws Exception {
        MockWebServer dead = new MockWebServer();
        dead.start();
        String baseUrl = dead.url("/").toString().replaceAll("/$", "");
        dead.shutdown();
        MockMvc deadMvc = buildMvc(baseUrl);

        deadMvc.perform(get("/v1/marketing/entity/{id}/report", "42"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_unavailable"))
                .andExpect(jsonPath("$.entityId").value("42"))
                .andExpect(jsonPath("$.upstreamStatus").value((Object) null));
    }

    // ==================================================================
    // entityId handling: non-numeric verbatim + URL-encoding + blank validation
    // ==================================================================

    @Test
    void entityId_nonNumericIsForwardedVerbatim_andSpecialCharsAreEncoded() throws Exception {
        enqueueJson(200, "{\"generatedAt\":\"now\",\"entityProfile\":{}}");

        // Ids are opaque strings, not assumed numeric.
        mvc.perform(get("/v1/marketing/entity-report/{id}", "ent x & co"))
                .andExpect(status().isOk());

        // space → %20, '&' → %26 so it is not parsed as a query separator
        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/entity-report/ent%20x%20%26%20co");
    }

    @Test
    void blankEntityId_returns400_withoutCallingUpstream() throws Exception {
        mvc.perform(get("/v1/marketing/entity-report/{id}", " "))
                .andExpect(status().isBadRequest());

        assertThat(upstream.getRequestCount()).isZero();
    }
}
