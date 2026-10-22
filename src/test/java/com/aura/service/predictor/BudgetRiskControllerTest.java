package com.aura.service.predictor;

import com.aura.service.currency.ExchangeRateUnavailableException;
import com.aura.service.currency.UsdInrRate;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BudgetRiskControllerTest {

    private static final String UPSTREAM_RESPONSE = """
            {"movieName":"New Film","releaseDate":"2026-12-25","language":"telugu","modelVersion":"2026-10-20",
             "ratingRange":1000,"hitRatio":2.0,"maxBudgetHitProbability":0.5,"safeBudgetHitProbability":0.75,
             "assessments":[{"rating":4.0,"maxBudget":null,"maxBudgetCapped":false,"safeBudget":null,
                             "safeBudgetCapped":false,"plannedBudget":30000000.0,"expectedRatio":0.8,
                             "hitProbability":0.2,"verdict":"RISK","verdictConfidence":0.8}],
             "breakout":null,"notes":["n1"]}""";

    private static final String UPSTREAM_RESPONSE_WITH_BREAKOUT = """
            {"movieName":"New Film","ratingRange":10000,"hitRatio":2.0,
             "assessments":[{"rating":5.0,"maxBudget":1234567.89,"maxBudgetCapped":true,"safeBudget":500000.0,
                             "safeBudgetCapped":false,"plannedBudget":null,"expectedRatio":null}],
             "breakout":{"ratingRange":100000,"note":"Breakout case",
                         "assessments":[{"rating":5.0,"maxBudget":2000000.0,"safeBudget":null}]},
             "notes":[]}""";

    // 80 rupees to the dollar, exact in binary floating point.
    private static final UsdInrRate RATE = new UsdInrRate(LocalDate.of(2026, 10, 21), 0.0125);

    private MockWebServer upstream;
    private final ObjectMapper mapper = new ObjectMapper();
    // Actor -> highest rating range among their last 3 films; null when the test sets none.
    private Integer leadRatingRange;
    private final List<String> lookups = new ArrayList<>();
    private boolean rateUnavailable;

    @BeforeEach
    void setUp() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.shutdown();
    }

    private MockMvc mvc() {
        AuraPredictorProperties props = new AuraPredictorProperties();
        props.setBaseUrl(upstream.url("/").toString().replaceAll("/$", ""));
        props.setConnectTimeoutMs(1_000);
        props.setReadTimeoutMs(2_000);
        AuraPredictorClient client = new AuraPredictorClient(
                new AuraPredictorClientConfig().auraPredictorWebClient(props), props, mapper);
        LeadActorRatingRangeLookup lookup = (actor, beforeDate) -> {
            lookups.add(actor + "|" + beforeDate);
            return leadRatingRange;
        };
        return MockMvcBuilders.standaloneSetup(
                new BudgetRiskController(new BudgetRiskService(client, lookup, () -> {
                    if (rateUnavailable) {
                        throw new ExchangeRateUnavailableException("No USD/INR exchange rate");
                    }
                    return RATE;
                }))).build();
    }

    private JsonNode send(String language, String marketingBudget) throws Exception {
        return send(language, marketingBudget, "");
    }

    private JsonNode send(String language, String marketingBudget, String extraFields) throws Exception {
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(UPSTREAM_RESPONSE));
        String mb = marketingBudget == null ? "" : ", \"marketingBudget\": \"" + marketingBudget + "\"";
        mvc().perform(post("/api/predictions/budget-risk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"movieName": "New Film", "releaseDate": "2026-12-25", "language": "%s",
                                 "estimatedBudget": 30000000, "leadActor": "Some Actor",
                                 "supportingActors": ["A", "B"]%s%s}
                                """.formatted(language, mb, extraFields)))
                .andExpect(status().isOk());
        RecordedRequest recorded = upstream.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo("/v1/budget-risk/upcoming");
        return mapper.readTree(recorded.getBody().readUtf8());
    }

    @Test
    void responseBudgetsAreConvertedToInr_restPassesThrough() throws Exception {
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(UPSTREAM_RESPONSE_WITH_BREAKOUT));
        mvc().perform(post("/api/predictions/budget-risk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieName\": \"New Film\", \"releaseDate\": \"2026-12-25\", \"language\": \"telugu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.usdToInrRate").value(80.0))
                .andExpect(jsonPath("$.exchangeRateDate").value("2026-10-21"))
                .andExpect(jsonPath("$.assessments[0].maxBudget").value(98_765_431.0))
                .andExpect(jsonPath("$.assessments[0].safeBudget").value(40_000_000.0))
                .andExpect(jsonPath("$.assessments[0].plannedBudget").isEmpty())
                .andExpect(jsonPath("$.assessments[0].maxBudgetCapped").value(true))
                .andExpect(jsonPath("$.assessments[0].expectedRatio").isEmpty())
                .andExpect(jsonPath("$.breakout.ratingRange").value(100_000))
                .andExpect(jsonPath("$.breakout.note").value("Breakout case"))
                .andExpect(jsonPath("$.breakout.assessments[0].maxBudget").value(160_000_000.0))
                .andExpect(jsonPath("$.breakout.assessments[0].safeBudget").isEmpty())
                .andExpect(jsonPath("$.ratingRange").value(10_000))
                .andExpect(jsonPath("$.hitRatio").value(2.0));
    }

    @Test
    void estimatedBudget_isInrByDefault_andUsdWhenSaidSo() throws Exception {
        assertThat(send("telugu", null).path("budget").asDouble()).isEqualTo(375_000d);
        assertThat(send("telugu", null, ", \"currency\": \"inr\"").path("budget").asDouble()).isEqualTo(375_000d);
        assertThat(send("telugu", null, ", \"currency\": \"USD\"").path("budget").asDouble()).isEqualTo(30_000_000d);
    }

    @Test
    void plannedBudget_roundTripsToTheInrSent() throws Exception {
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(UPSTREAM_RESPONSE));
        // 30,000,000 INR goes upstream as 375,000 USD; the stub echoes 30,000,000 USD back, i.e. 2.4e9 INR.
        mvc().perform(post("/api/predictions/budget-risk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieName\": \"F\", \"releaseDate\": \"2026-12-25\", \"language\": \"telugu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessments[0].plannedBudget").value(2_400_000_000.0))
                .andExpect(jsonPath("$.assessments[0].maxBudget").isEmpty());
    }

    @Test
    void noExchangeRate_returns503WithoutCallingUpstream() throws Exception {
        rateUnavailable = true;
        mvc().perform(post("/api/predictions/budget-risk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieName\": \"F\", \"releaseDate\": \"2026-12-25\", \"language\": \"telugu\"}"))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.error").value("exchange_rate_unavailable"));
        assertThat(upstream.getRequestCount()).isZero();
    }

    @Test
    void mapsUiFieldsAndDefaultsMarketingBudgetToMedium() throws Exception {
        JsonNode sent = send("telugu", null, ", \"currency\": \"USD\"");
        assertThat(sent.path("budget").asDouble()).isEqualTo(30_000_000d);
        assertThat(sent.has("currency")).isFalse();
        assertThat(sent.has("estimatedBudget")).isFalse();
        assertThat(sent.has("marketingBudget")).isFalse();
        assertThat(sent.path("hitRatio").asDouble()).isEqualTo(2.0);
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(1_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(4, 6, 8);
        assertThat(sent.path("supportingActors").size()).isEqualTo(2);
        assertThat(sent.has("director")).isFalse();
        assertThat(lookups).containsExactly("Some Actor|2026-12-25");
    }

    @Test
    void marketingBudgetBands_differForHindi() throws Exception {
        assertThat(send("hindi", "low").path("ratingRange").asInt()).isEqualTo(1_000);
        assertThat(send("Hindi, Tamil", "MEDIUM").path("ratingRange").asInt()).isEqualTo(10_000);
        assertThat(send("hindi", "high").path("ratingRange").asInt()).isEqualTo(100_000);
        assertThat(send("tamil", "low").path("ratingRange").asInt()).isEqualTo(100);
        assertThat(send("tamil, hindi", "medium").path("ratingRange").asInt()).isEqualTo(1_000);
        assertThat(send("kannada", "high").path("ratingRange").asInt()).isEqualTo(10_000);
    }

    @Test
    void leadActorsHigherRecentRatingRange_wins_andShiftsRatings() throws Exception {
        leadRatingRange = 100_000;
        JsonNode sent = send("telugu", "medium");
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(100_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(5, 7, 9);

        leadRatingRange = 10_000;
        sent = send("telugu", "low");
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(10_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(5, 7, 9);
    }

    @Test
    void leadActorsLowerRecentRatingRange_keepsMarketingBand() throws Exception {
        leadRatingRange = 100;
        JsonNode sent = send("telugu", "high");
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(10_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(4, 6, 8);

        // 10000+ shifts the ratings even when the marketing band is already as high.
        leadRatingRange = 10_000;
        sent = send("hindi", "high");
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(100_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(5, 7, 9);
    }

    @Test
    void invalidInput_isRejectedBeforeCallingUpstream() throws Exception {
        for (String body : List.of(
                "{\"movieName\": \"F\", \"releaseDate\": \"25-12-2026\", \"language\": \"telugu\"}",
                "{\"movieName\": \"F\", \"releaseDate\": \"2026-12-25\", \"language\": \"telugu\", \"marketingBudget\": \"huge\"}",
                "{\"movieName\": \"F\", \"releaseDate\": \"2026-12-25\", \"language\": \"telugu\", \"currency\": \"EUR\"}",
                "{\"movieName\": \"F\", \"releaseDate\": \"2026-12-25\", \"language\": \"telugu\", \"supportingActors\": [\"a\",\"b\",\"c\",\"d\",\"e\"]}")) {
            mvc().perform(post("/api/predictions/budget-risk").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(upstream.getRequestCount()).isZero();
    }

    @Test
    void upstream503_passesThrough() throws Exception {
        upstream.enqueue(new MockResponse().setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\": \"No trained budget-risk model\"}"));
        mvc().perform(post("/api/predictions/budget-risk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieName\": \"F\", \"releaseDate\": \"2026-12-25\", \"language\": \"telugu\"}"))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.error").value("model_not_available"))
                .andExpect(jsonPath("$.message").value("No trained budget-risk model"));
    }
}
