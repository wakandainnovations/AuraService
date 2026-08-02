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

class AuraMathGraphProxyControllerTest {

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

        AuraMathGraphProxyController controller = new AuraMathGraphProxyController(proxyService, props);
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
    void graphUsers_languageOnly_happyPath() throws Exception {
        enqueueJson(200, "{\"nodes\":[],\"edges\":[],\"summary\":{\"totalUsers\":0}}");

        mvc.perform(get("/v1/graph/users").param("language", "Tamil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalUsers").value(0));

        assertThat(takeRequest().getPath()).isEqualTo("/api/graph/users?language=Tamil");
    }

    @Test
    void graphUsers_withMovieFilter_appendsEncodedMovieParam() throws Exception {
        enqueueJson(200, "{\"nodes\":[],\"edges\":[]}");

        mvc.perform(get("/v1/graph/users").param("language", "Tamil").param("movie", "Vikram 2"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/graph/users?language=Tamil&movie=Vikram+2");
    }

    @Test
    void graphUsers_unknownLanguage_upstream404IsRelayedThrough() throws Exception {
        enqueueJson(404, "{\"error\":\"no matching language\"}");

        mvc.perform(get("/v1/graph/users").param("language", "Klingon"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no matching language"));
    }

    @Test
    void graphUsers_missingLanguage_returns400WithoutCallingUpstream() throws Exception {
        mvc.perform(get("/v1/graph/users"))
                .andExpect(status().isBadRequest());

        assertThat(upstream.getRequestCount()).isZero();
    }

    @Test
    void graphUsers_upstream500_isMappedToSanitized502() throws Exception {
        enqueueJson(500, "{\"message\":\"PSQLException: boom\",\"path\":\"/api/graph/users\"}");

        mvc.perform(get("/v1/graph/users").param("language", "Tamil"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_failure"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void graphUsers_cachedOnSecondCall() throws Exception {
        enqueueJson(200, "{\"nodes\":[],\"edges\":[]}");

        mvc.perform(get("/v1/graph/users").param("language", "Tamil")).andExpect(status().isOk());
        mvc.perform(get("/v1/graph/users").param("language", "Tamil")).andExpect(status().isOk());

        assertThat(upstream.getRequestCount()).isEqualTo(1);
    }
}
