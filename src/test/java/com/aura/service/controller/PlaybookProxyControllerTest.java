package com.aura.service.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link PlaybookProxyController} against a real {@link AuraMathProxyService} backed by a
 * {@link MockWebServer} (same style as {@code MarketingAggregationControllerTest}). Not entity
 * scoped, so there is no {@code EntityAccessService} dependency to mock here.
 */
class PlaybookProxyControllerTest {

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

        AuraMathClientConfig config = new AuraMathClientConfig();
        var provider = config.auraMathConnectionProvider();
        AuraMathProxyService proxyService = new AuraMathProxyService(
                config.auraMathWebClient(props, provider),
                config.auraMathSyncWebClient(props, provider),
                props,
                mapper
        );

        PlaybookProxyController controller = new PlaybookProxyController(proxyService, props);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
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

    @Test
    void playbook_forwardsIndustryAndLanguageQueryParams() throws Exception {
        enqueueJson("{\"status\":\"ok\",\"cohort\":\"Kollywood|Tamil\",\"resolvedCohort\":\"Kollywood|Tamil\","
                + "\"usedPooledFallback\":false,\"patterns\":[]}");

        mvc.perform(get("/api/marketing/playbook")
                        .param("industry", "Kollywood")
                        .param("language", "Tamil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedPooledFallback").value(false));

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).contains("/api/marketing/playbook");
        assertThat(req.getPath()).contains("industry=Kollywood");
        assertThat(req.getPath()).contains("language=Tamil");
    }

    // ------------------------------------------------------------------
    // Cohort resolution fallback (computed upstream in AuraMath) passes through unmodified
    // ------------------------------------------------------------------
    @Test
    void playbook_cohortFallbackToAll_passedThroughUnchanged() throws Exception {
        // Simulates AuraMath's F7 behavior: no cohort-specific playbook_patterns rows, so it
        // pooled to cohort='ALL' - the proxy must relay that fact through unmodified, not mask it.
        enqueueJson("{\"status\":\"ok\",\"cohort\":\"Mollywood|Malayalam\",\"resolvedCohort\":\"ALL\","
                + "\"usedPooledFallback\":true,\"patterns\":[{\"patternSequence\":[\"teaser\",\"trailer\"]}]}");

        mvc.perform(get("/api/marketing/playbook")
                        .param("industry", "Mollywood")
                        .param("language", "Malayalam"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohort").value("Mollywood|Malayalam"))
                .andExpect(jsonPath("$.resolvedCohort").value("ALL"))
                .andExpect(jsonPath("$.usedPooledFallback").value(true))
                .andExpect(jsonPath("$.patterns.length()").value(1));
    }

    @Test
    void playbook_insufficientHistory_returnsExplicitStatusNotEmptyBody() throws Exception {
        enqueueJson("{\"status\":\"insufficient_history\",\"details\":\"no rows for this cohort or ALL\"}");

        mvc.perform(get("/api/marketing/playbook")
                        .param("industry", "Ghostwood")
                        .param("language", "Klingon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("insufficient_history"))
                .andExpect(jsonPath("$.details").exists())
                .andExpect(jsonPath("$.patterns").doesNotExist());
    }
}
