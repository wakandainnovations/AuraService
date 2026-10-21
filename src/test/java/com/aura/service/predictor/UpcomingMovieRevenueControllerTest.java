package com.aura.service.predictor;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UpcomingMovieRevenueControllerTest {

    private static final String REQUEST = """
            {"movieName": "New Film", "releaseDate": "2026-12-25", "language": "telugu",
             "budget": 40000000, "runtime": 150, "leadActor": "Some Actor",
             "supportingActors": ["A", "B"], "isRemake": true}
            """;

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
        AuraPredictorProperties props = new AuraPredictorProperties();
        props.setBaseUrl(baseUrl);
        props.setConnectTimeoutMs(1_000);
        props.setReadTimeoutMs(2_000);
        AuraPredictorClient client = new AuraPredictorClient(
                new AuraPredictorClientConfig().auraPredictorWebClient(props), props, mapper);
        return MockMvcBuilders.standaloneSetup(new UpcomingMovieRevenueController(client)).build();
    }

    @Test
    void success_forwardsRequestWithoutNulls_andReturnsPredictions() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"movieName": "New Film", "releaseDate": "2026-12-25", "language": "telugu",
                         "modelVersion": "2026-10-19",
                         "predictions": [{"rating": 4.0, "revenue": 1000.5, "low": 500.0, "high": 2000.0},
                                         {"rating": 8.0, "revenue": 3000.0, "low": 1500.0, "high": 6000.0}],
                         "notes": ["Only 2 of 4 supporting actors given"]}
                        """));

        mvc.perform(post("/api/predictions/upcoming-movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value("2026-10-19"))
                .andExpect(jsonPath("$.predictions[0].rating").value(4.0))
                .andExpect(jsonPath("$.predictions[0].revenue").value(1000.5))
                .andExpect(jsonPath("$.predictions[1].high").value(6000.0))
                .andExpect(jsonPath("$.notes[0]").value("Only 2 of 4 supporting actors given"));

        RecordedRequest recorded = upstream.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/predict/upcoming");
        JsonNode sent = mapper.readTree(recorded.getBody().readUtf8());
        assertThat(sent.path("movieName").asText()).isEqualTo("New Film");
        assertThat(sent.path("budget").asDouble()).isEqualTo(40_000_000d);
        assertThat(sent.path("isRemake").asBoolean()).isTrue();
        assertThat(sent.path("supportingActors").size()).isEqualTo(2);
        // Unset optionals are omitted so AuraPredictor applies its own defaults.
        assertThat(sent.has("ratings")).isFalse();
        assertThat(sent.has("director")).isFalse();
    }

    @Test
    void missingRuntime_isRejectedBeforeCallingUpstream() throws Exception {
        mvc.perform(post("/api/predictions/upcoming-movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"movieName": "New Film", "releaseDate": "2026-12-25",
                                 "language": "telugu", "budget": 40000000}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(upstream.getRequestCount()).isZero();
    }

    @Test
    void upstream422_passesThroughWithDetail() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\": \"rating_range must be one of (1, 10, 100, 1000, 10000, 100000), got 5\"}"));

        mvc.perform(post("/api/predictions/upcoming-movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value("invalid_prediction_input"))
                .andExpect(jsonPath("$.message").value(
                        "rating_range must be one of (1, 10, 100, 1000, 10000, 100000), got 5"));
    }

    @Test
    void upstream503_meansModelNotTrained() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\": \"No trained model found\"}"));

        mvc.perform(post("/api/predictions/upcoming-movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.error").value("model_not_available"));
    }

    @Test
    void upstream500_isSanitizedTo502() throws Exception {
        upstream.enqueue(new MockResponse().setResponseCode(500).setBody("Traceback ..."));

        mvc.perform(post("/api/predictions/upcoming-movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.error").value("upstream_failure"));
    }

    @Test
    void connectionRefused_returns504() throws Exception {
        MockWebServer ephemeral = new MockWebServer();
        ephemeral.start();
        String closedUrl = ephemeral.url("/").toString().replaceAll("/$", "");
        ephemeral.shutdown();

        buildMvc(closedUrl).perform(post("/api/predictions/upcoming-movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().is(504))
                .andExpect(jsonPath("$.error").value("upstream_unavailable"));
    }
}
