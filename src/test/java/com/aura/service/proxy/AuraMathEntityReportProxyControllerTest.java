package com.aura.service.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the entity intelligence report wrappers, which now have two distinct shapes:
 * <ul>
 *   <li>{@code /v1/marketing/entity-report/{id}} (shareable) → upstream PDF endpoint:
 *       binary 200 pass-through (bytes + Content-Disposition), text/plain 404 relay,
 *       5xx → 502 envelope, connection failure → 502.</li>
 *   <li>{@code /v1/marketing/entity/{id}/report} (in-app) → JSON report: full report 200,
 *       "No entity found" 200 → 404 translation, no-scored-history 200 pass-through,
 *       5xx → 502 envelope, connection failure → 502.</li>
 * </ul>
 * Plus shared entityId validation/encoding.
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

    private void enqueueBinary(int code, String contentType, byte[] body, String contentDisposition) {
        MockResponse response = new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", contentType)
                .setBody(new Buffer().write(body));
        if (contentDisposition != null) {
            response.setHeader("Content-Disposition", contentDisposition);
        }
        upstream.enqueue(response);
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = upstream.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        return req;
    }

    // ==================================================================
    // Shareable report (PDF endpoint) — binary-safe path
    // ==================================================================

    @Test
    void shareableReport_pdf_returns200_forwardsBytesAndDispositionVerbatim() throws Exception {
        // Includes non-UTF-8 bytes (0xDE 0xAD 0xBE 0xEF 0x00 0xFF) — these would be corrupted if the
        // body were ever decoded through a String/charset, so an exact match proves binary safety.
        byte[] pdf = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n',
                (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, (byte) 0x00, (byte) 0xFF};
        String disposition = "inline; filename=\"42-intelligence-report.pdf\"";
        enqueueBinary(200, "application/pdf", pdf, disposition);

        MvcResult result = mvc.perform(get("/v1/marketing/entity-report/{id}", "42"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", disposition))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(pdf);
        // Wrapper targets the upstream /pdf endpoint.
        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/entity-report/42/pdf");
    }

    @Test
    void shareableReport_notFound_relaysTextPlain404() throws Exception {
        // Upstream returns a real HTTP 404 with a short text/plain message — relayed as-is,
        // no body sniffing required.
        upstream.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody("No report available for this entity"));

        mvc.perform(get("/v1/marketing/entity-report/{id}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("No report available for this entity"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/entity-report/nope/pdf");
    }

    @Test
    void shareableReport_upstream5xx_isMappedTo502Envelope() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>upstream boom</body></html>"));

        mvc.perform(get("/v1/marketing/entity-report/{id}", "42"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("upstream_failure"))
                .andExpect(jsonPath("$.entityId").value("42"))
                .andExpect(jsonPath("$.upstreamStatus").value(503));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/entity-report/42/pdf");
    }

    @Test
    void shareableReport_connectionRefused_returns502_withNullUpstreamStatus() throws Exception {
        MockWebServer dead = new MockWebServer();
        dead.start();
        String baseUrl = dead.url("/").toString().replaceAll("/$", "");
        dead.shutdown();
        MockMvc deadMvc = buildMvc(baseUrl);

        deadMvc.perform(get("/v1/marketing/entity-report/{id}", "42"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_unavailable"))
                .andExpect(jsonPath("$.entityId").value("42"))
                .andExpect(jsonPath("$.upstreamStatus").value((Object) null));
    }

    // ==================================================================
    // In-app report (JSON endpoint)
    // ==================================================================

    @Test
    void inAppReport_fullReport_returns200_hitsReportRoute() throws Exception {
        enqueueJson(200, "{\"generatedAt\":\"2026-06-06T00:00:00Z\",\"entityProfile\":{}}");

        mvc.perform(get("/v1/marketing/entity/{id}/report", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists());

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/entity/42/report");
    }

    @Test
    void inAppReport_unknownEntity_isTranslatedTo404() throws Exception {
        enqueueJson(200, "{\"entityId\":\"nope\",\"message\":\"No entity found for this id\"}");

        mvc.perform(get("/v1/marketing/entity/{id}/report", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No entity found for this id"));
    }

    @Test
    void inAppReport_noScoredHistory_passesThroughAs200() throws Exception {
        String body = "{\"entityId\":\"7\",\"name\":\"Quiet Star\",\"trackedKeywords\":[\"x\"],"
                + "\"message\":\"No scored post history found for this entity yet\"}";
        enqueueJson(200, body);

        mvc.perform(get("/v1/marketing/entity/{id}/report", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Quiet Star"))
                .andExpect(jsonPath("$.message").value("No scored post history found for this entity yet"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/entity/7/report");
    }

    @Test
    void inAppReport_upstream500_isMappedTo502_withEnvelope() throws Exception {
        enqueueJson(500, "{\"status\":500,\"message\":\"PSQLException: relation does not exist\"}");

        mvc.perform(get("/v1/marketing/entity/{id}/report", "42"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_failure"))
                .andExpect(jsonPath("$.entityId").value("42"))
                .andExpect(jsonPath("$.upstreamStatus").value(500))
                // upstream message/SQL fragments must not leak through
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void inAppReport_connectionRefused_returns502_withNullUpstreamStatus() throws Exception {
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
        enqueueBinary(200, "application/pdf", new byte[]{'%', 'P', 'D', 'F'}, null);

        // Ids are opaque strings, not assumed numeric.
        mvc.perform(get("/v1/marketing/entity-report/{id}", "ent x & co"))
                .andExpect(status().isOk());

        // space → %20, '&' → %26 so it is not parsed as a query separator; /pdf suffix preserved.
        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/entity-report/ent%20x%20%26%20co/pdf");
    }

    @Test
    void blankEntityId_returns400_withoutCallingUpstream() throws Exception {
        mvc.perform(get("/v1/marketing/entity-report/{id}", " "))
                .andExpect(status().isBadRequest());

        assertThat(upstream.getRequestCount()).isZero();
    }
}
