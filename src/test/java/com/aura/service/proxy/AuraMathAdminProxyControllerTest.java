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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuraMathAdminProxyControllerTest {

    private MockWebServer upstream;
    private MockMvc mvc;
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
        props.setMarketingTimeoutMs(5_000);

        AuraMathClientConfig config = new AuraMathClientConfig();
        var provider = config.auraMathConnectionProvider();
        AuraMathProxyService proxyService = new AuraMathProxyService(
                config.auraMathWebClient(props, provider),
                config.auraMathSyncWebClient(props, provider),
                props,
                mapper
        );

        AuraMathAdminProxyController controller = new AuraMathAdminProxyController(proxyService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.shutdown();
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = upstream.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        return req;
    }

    @Test
    void runEnrichment_happyPath_forwardsPlainTextBody() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain")
                .setBody("done"));

        mvc.perform(post("/v1/admin/run-enrichment"))
                .andExpect(status().isOk())
                .andExpect(content().string("done"));

        RecordedRequest req = takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/admin/run-enrichment");
    }

    @Test
    void runEngagementRating_happyPath() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"updated\":412}"));

        mvc.perform(post("/v1/admin/run-engagement-rating"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/admin/run-engagement-rating");
    }

    @Test
    void runGraphPopulation_happyPath() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"nodes\":100,\"edges\":250}"));

        mvc.perform(post("/v1/admin/run-graph-population"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/admin/run-graph-population");
    }

    @Test
    void resolveIdentities_happyPath_forwardsPlainTextBody() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain")
                .setBody("inserted=42"));

        mvc.perform(post("/v1/admin/resolve-identities"))
                .andExpect(status().isOk())
                .andExpect(content().string("inserted=42"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/admin/resolve-identities");
    }

    @Test
    void recomputeNarrativeNovelty_happyPath() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"rebuilt\":900}"));

        mvc.perform(post("/v1/admin/recompute-narrative-novelty"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/admin/recompute-narrative-novelty");
    }

    @Test
    void recomputeNarrativeNoveltyV1_happyPath() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"rebuilt\":900}"));

        mvc.perform(post("/v1/admin/recompute-narrative-novelty-v1"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/admin/recompute-narrative-novelty-v1");
    }

    @Test
    void recomputeConflictBalance_happyPath() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"scored\":900}"));

        mvc.perform(post("/v1/admin/recompute-conflict-balance"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/admin/recompute-conflict-balance");
    }

    @Test
    void runEnrichment_upstream500_isWrappedWithOriginalStatusPreserved() throws Exception {
        // forwardPost (the shared "wrap everything non-2xx" contract used across
        // AuraMathProxyController/AuraMathAdminProxyController) preserves the upstream's actual
        // status code and nests the body under upstreamBody, rather than remapping to 502 the way
        // the marketing-style helpers do.
        upstream.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"PSQLException: boom\"}"));

        mvc.perform(post("/v1/admin/run-enrichment"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.upstreamStatus").value(500))
                .andExpect(jsonPath("$.upstreamBody.message").value("PSQLException: boom"));
    }
}
