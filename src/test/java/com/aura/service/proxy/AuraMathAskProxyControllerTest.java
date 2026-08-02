package com.aura.service.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuraMathAskProxyControllerTest {

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

        AuraMathAskProxyController controller = new AuraMathAskProxyController(proxyService, props);
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
    void databases_happyPath() throws Exception {
        enqueueJson(200, "{\"databases\":[{\"name\":\"orders\",\"driver\":\"postgresql\",\"host\":\"db.internal:5432\"}]}");

        mvc.perform(get("/v1/ask/databases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databases[0].name").value("orders"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/ask/databases");
    }

    @Test
    void databases_cachedOnSecondCall() throws Exception {
        enqueueJson(200, "{\"databases\":[]}");

        mvc.perform(get("/v1/ask/databases")).andExpect(status().isOk());
        mvc.perform(get("/v1/ask/databases")).andExpect(status().isOk());

        assertThat(upstream.getRequestCount()).isEqualTo(1);
    }

    @Test
    void testConnection_happyPath_forwardsBody() throws Exception {
        enqueueJson(200, "{\"connected\":true,\"databaseProductName\":\"PostgreSQL\"}");

        mvc.perform(post("/v1/ask/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jdbcUrl\":\"jdbc:postgresql://localhost:5432/analytics\",\"username\":\"ro\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));

        RecordedRequest req = takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/ask/test-connection");
        assertThat(req.getBody().readUtf8()).contains("jdbc:postgresql");
    }

    @Test
    void testConnection_upstream400_isRelayedVerbatim() throws Exception {
        enqueueJson(400, "{\"connected\":false,\"error\":\"Unsupported JDBC URL scheme. Allowed: jdbc:postgresql:, jdbc:sqlite:, jdbc:mysql:\"}");

        mvc.perform(post("/v1/ask/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jdbcUrl\":\"jdbc:h2:mem:test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void ask_happyPath_forwardsQuestionAndAnswer() throws Exception {
        enqueueJson(200, "{\"requestId\":\"abc\",\"answer\":\"42 orders last month\",\"clarificationNeeded\":false}");

        mvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"how many orders last month?\",\"databases\":[\"orders\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("42 orders last month"));

        RecordedRequest req = takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/ask");
        assertThat(req.getBody().readUtf8()).contains("how many orders last month");
    }

    @Test
    void ask_clarificationNeeded_upstream400IsRelayedVerbatim_notWrapped() throws Exception {
        // The Ask engine's rich clarification contract must survive unmodified — not collapsed
        // into a generic {upstreamStatus, upstreamBody} envelope.
        enqueueJson(400, "{\"requestId\":\"b2c3\",\"clarificationNeeded\":true,"
                + "\"clarificationQuestion\":\"Which table holds the data you mean?\",\"answer\":null}");

        mvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"how many refunds?\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.clarificationNeeded").value(true))
                .andExpect(jsonPath("$.clarificationQuestion").exists())
                .andExpect(jsonPath("$.requestId").value("b2c3"));
    }

    @Test
    void ask_upstream503EngineDisabled_isSanitizedTo502() throws Exception {
        // Like every other 5xx, this is uniformly sanitized rather than passed through verbatim —
        // consistent with how the rest of the proxy surface treats any upstream 5xx (see
        // AuraMathMarketingProxyControllerTest / AuraMathAnalyticsProxyControllerTest). Only the
        // Ask engine's documented 4xx contracts (400 clarification, etc.) are relayed unmodified.
        enqueueJson(503, "{\"error\":\"the Ask engine is disabled\"}");

        mvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"x\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_failure"));
    }

    @Test
    void ask_upstream500_isSanitizedTo502() throws Exception {
        enqueueJson(500, "{\"message\":\"PSQLException: boom\",\"path\":\"/api/ask\"}");

        mvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"x\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_failure"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void metrics_happyPath_neverCached() throws Exception {
        enqueueJson(200, "{\"requests\":10,\"answers\":8}");
        enqueueJson(200, "{\"requests\":11,\"answers\":9}");

        mvc.perform(get("/v1/ask/admin/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").value(10));
        mvc.perform(get("/v1/ask/admin/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").value(11));

        assertThat(upstream.getRequestCount()).isEqualTo(2);
    }
}
