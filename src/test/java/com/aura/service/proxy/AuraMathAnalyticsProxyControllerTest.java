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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuraMathAnalyticsProxyControllerTest {

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

        AuraMathAnalyticsProxyController controller =
                new AuraMathAnalyticsProxyController(proxyService, props);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.shutdown();
    }

    private void enqueueJson(int code, String json) {
        upstream.enqueue(new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(json));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = upstream.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        return req;
    }

    @Test
    void listCelebrities_happyPath_forwardsUpstreamPayload() throws Exception {
        enqueueJson(200, "{\"type\":\"CELEBRITY\",\"total\":1,\"entities\":[{\"id\":\"42\",\"name\":\"Rajinikanth\"}]}");

        mvc.perform(get("/v1/analytics/celebrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entities[0].name").value("Rajinikanth"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/analytics/celebrity");
    }

    @Test
    void celebrityAnalytics_happyPath_forwardsFullAnalytics() throws Exception {
        enqueueJson(200, "{\"id\":\"42\",\"type\":\"CELEBRITY\",\"sentiment\":{\"positive\":0.8}}");

        mvc.perform(get("/v1/analytics/celebrity/{id}", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("42"))
                .andExpect(jsonPath("$.sentiment.positive").value(0.8));

        assertThat(takeRequest().getPath()).isEqualTo("/api/analytics/celebrity/42");
    }

    @Test
    void celebrityAnalytics_upstream404_isForwardedThrough() throws Exception {
        // Unknown id, or an entity whose type is not CELEBRITY: upstream returns a real 404.
        enqueueJson(404, "{\"error\":\"not_found\",\"entityId\":\"999\"}");

        mvc.perform(get("/v1/analytics/celebrity/{id}", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/analytics/celebrity/999");
    }

    @Test
    void celebrityAnalytics_urlEncodesNonNumericId() throws Exception {
        enqueueJson(200, "{\"id\":\"A R Rahman\",\"type\":\"CELEBRITY\"}");

        mvc.perform(get("/v1/analytics/celebrity/{id}", "A R Rahman"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/analytics/celebrity/A%20R%20Rahman");
    }

    @Test
    void celebrityAnalytics_upstream500_isMappedToSanitized502() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":500,\"message\":\"PSQLException: relation does not exist\",\"path\":\"/api/analytics/celebrity/42\"}"));

        mvc.perform(get("/v1/analytics/celebrity/{id}", "42"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_failure"))
                .andExpect(jsonPath("$.upstream_path").value("/api/analytics/celebrity/42"))
                // The sanitized body must NOT echo the upstream message/SQL fragments.
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void cachedAnalytics_doesNotCallUpstreamSecondTime() throws Exception {
        enqueueJson(200, "{\"id\":\"42\",\"type\":\"CELEBRITY\"}");

        mvc.perform(get("/v1/analytics/celebrity/{id}", "42")).andExpect(status().isOk());
        mvc.perform(get("/v1/analytics/celebrity/{id}", "42")).andExpect(status().isOk());

        assertThat(upstream.getRequestCount()).isEqualTo(1);
    }
}
