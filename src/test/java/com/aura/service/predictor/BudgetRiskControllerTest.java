package com.aura.service.predictor;

import com.aura.service.currency.ExchangeRateUnavailableException;
import com.aura.service.currency.UsdInrRate;
import com.aura.service.predictor.RecentRatingRangeLookup.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aura.service.predictor.SimilarBudgetTalentLookup.SimilarBudgetFilm;
import okhttp3.mockwebserver.Dispatcher;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
    // Highest rating range among each role's last 3 films; absent when the test sets none.
    private final Map<Role, Integer> ratingRanges = new EnumMap<>(Role.class);
    private final List<String> lookups = new ArrayList<>();
    // Similar-budget films per role, best first, as the lookup returns them.
    private final Map<Role, List<SimilarBudgetFilm>> similarBudgetFilms = new EnumMap<>(Role.class);
    private final List<String> similarBudgetLookups = new ArrayList<>();
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
        RecentRatingRangeLookup lookup = (role, name, beforeDate) -> {
            lookups.add(role + "|" + name + "|" + beforeDate);
            return ratingRanges.get(role);
        };
        return MockMvcBuilders.standaloneSetup(
                new BudgetRiskController(new BudgetRiskService(client, lookup, () -> {
                    if (rateUnavailable) {
                        throw new ExchangeRateUnavailableException("No USD/INR exchange rate");
                    }
                    return RATE;
                }, (role, minBudget, maxBudget, minRevenueRatio, language, panIndia, recentSince, beforeDate,
                        limit) -> {
                    similarBudgetLookups.add(String.join("|", role.name(), String.valueOf(minBudget),
                            String.valueOf(maxBudget), String.valueOf(minRevenueRatio), language, String.valueOf(panIndia), recentSince, beforeDate,
                            String.valueOf(limit)));
                    return similarBudgetFilms.getOrDefault(role, List.of());
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
        // 30M USD with no proven talent: 2 - 0.65 * 29/34.
        assertThat(sent.path("hitRatio").asDouble()).isEqualTo(1.45);
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(1_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(4, 6, 8);
        assertThat(sent.path("supportingActors").size()).isEqualTo(2);
        assertThat(sent.has("director")).isFalse();
        assertThat(lookups).containsExactly("LEAD_ACTOR|Some Actor|2026-12-25");
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
        ratingRanges.put(Role.LEAD_ACTOR, 100_000);
        JsonNode sent = send("telugu", "medium");
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(100_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(5, 7, 9);

        ratingRanges.put(Role.LEAD_ACTOR, 10_000);
        sent = send("telugu", "low");
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(10_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(5, 7, 9);
    }

    @Test
    void leadActorsLowerRecentRatingRange_keepsMarketingBand() throws Exception {
        ratingRanges.put(Role.LEAD_ACTOR, 100);
        JsonNode sent = send("telugu", "high");
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(10_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(4, 6, 8);

        // 10000+ shifts the ratings even when the marketing band is already as high.
        ratingRanges.put(Role.LEAD_ACTOR, 10_000);
        sent = send("hindi", "high");
        assertThat(sent.path("ratingRange").asInt()).isEqualTo(100_000);
        assertThat(mapper.convertValue(sent.path("ratings"), double[].class)).containsExactly(5, 7, 9);
    }

    @Test
    void hitRatio_fallsInAStraightLineWithBudget() {
        assertThat(BudgetRiskService.budgetHitRatio(null)).isEqualTo(2.0);
        assertThat(BudgetRiskService.budgetHitRatio(500_000d)).isEqualTo(2.0);
        assertThat(BudgetRiskService.budgetHitRatio(1_000_000d)).isEqualTo(2.0);
        assertThat(BudgetRiskService.budgetHitRatio(18_000_000d)).isCloseTo(1.675, within(1e-9));
        assertThat(BudgetRiskService.budgetHitRatio(35_000_000d)).isEqualTo(1.35);
        assertThat(BudgetRiskService.budgetHitRatio(100_000_000d)).isEqualTo(1.35);
    }

    @Test
    void hitRatio_isInrBudgetConvertedFirst() throws Exception {
        // 30M INR is 375k USD, under $1M.
        assertThat(send("telugu", null).path("hitRatio").asDouble()).isEqualTo(2.0);
    }

    @Test
    void provenTalent_lowersHitRatio_leadWeighingMost() throws Exception {
        String usdWithCrew = ", \"currency\": \"USD\", \"director\": \" Dir \", \"musicDirector\": \"Composer\"";
        // Base at 30M USD is 1.4456; the cut is 35% x (0.5 lead + 0.3 director + 0.2 music director).
        assertThat(send("telugu", null, usdWithCrew).path("hitRatio").asDouble()).isEqualTo(1.45);
        assertThat(lookups).containsExactly(
                "LEAD_ACTOR|Some Actor|2026-12-25", "DIRECTOR|Dir|2026-12-25", "MUSIC_DIRECTOR|Composer|2026-12-25");

        ratingRanges.put(Role.LEAD_ACTOR, 10_000);
        assertThat(send("telugu", null, usdWithCrew).path("hitRatio").asDouble()).isEqualTo(1.19);

        // 1000+ counts half; below 1000 not at all.
        ratingRanges.put(Role.LEAD_ACTOR, 1_000);
        assertThat(send("telugu", null, usdWithCrew).path("hitRatio").asDouble()).isEqualTo(1.32);
        ratingRanges.put(Role.LEAD_ACTOR, 100);
        ratingRanges.put(Role.DIRECTOR, 100_000);
        ratingRanges.put(Role.MUSIC_DIRECTOR, 1_000);
        assertThat(send("telugu", null, usdWithCrew).path("hitRatio").asDouble()).isEqualTo(1.24);

        ratingRanges.put(Role.LEAD_ACTOR, 10_000);
        ratingRanges.put(Role.MUSIC_DIRECTOR, 10_000);
        // A full 35% cut would give 0.94; AuraPredictor's floor is 1.
        JsonNode sent = send("telugu", null, usdWithCrew);
        assertThat(sent.path("hitRatio").asDouble()).isEqualTo(1.0);
        assertThat(sent.path("director").asText()).isEqualTo("Dir");
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

    private static SimilarBudgetFilm film(String name) {
        return new SimilarBudgetFilm(name, name + " Film", "2024-05-01", "tamil", 1_000_000, 3_000_000);
    }

    /** Answers each call with a hit probability picked by the music director, else lead actor, it names. */
    private void scoreBy(Map<String, Double> hitProbabilities) {
        upstream.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    JsonNode body = mapper.readTree(request.getBody().readUtf8());
                    Double p = hitProbabilities.get(body.path("musicDirector").asText());
                    if (p == null) {
                        p = hitProbabilities.get(body.path("leadActor").asText());
                    }
                    if (p == null) {
                        return new MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
                                .setBody("{\"detail\": \"bad\"}");
                    }
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                            {"ratingRange":1000,"assessments":[
                              {"rating":4.0,"maxBudget":1000.0,"plannedBudget":375000.0,"hitProbability":%s},
                              {"rating":6.0,"maxBudget":2000.0,"plannedBudget":375000.0,"hitProbability":%s}]}"""
                            .formatted(p, p));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private org.springframework.test.web.servlet.ResultActions assessWithCrew(String language) throws Exception {
        return assessWithCrew(language, "2099-12-25");
    }

    private org.springframework.test.web.servlet.ResultActions assessWithCrew(String language, String releaseDate)
            throws Exception {
        return mvc().perform(post("/api/predictions/budget-risk")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"movieName": "New Film", "releaseDate": "%s", "language": "%s",
                         "estimatedBudget": 30000000, "leadActor": "Some Actor", "supportingActors": ["Side Kick"],
                         "musicDirector": "Composer"}
                        """.formatted(releaseDate, language)));
    }

    @Test
    void alternatives_listSimilarBudgetTalentThatBeatsTheRequest_bestFirst() throws Exception {
        similarBudgetFilms.put(Role.LEAD_ACTOR, List.of(
                film("Star A"), film("side kick"), film("Star B"), film("Weak C"), film("Broken D")));
        similarBudgetFilms.put(Role.MUSIC_DIRECTOR, List.of(film("composer"), film("Tunes E")));
        Map<String, Double> scores = Map.of(
                "Some Actor", 0.25, "Star A", 0.5, "Star B", 0.75, "Weak C", 0.125, "Tunes E", 0.375);
        scoreBy(scores);

        assessWithCrew("Tamil")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessments[0].maxBudget").value(80_000.0))
                .andExpect(jsonPath("$.alternatives.averageHitProbability").value(0.25))
                .andExpect(jsonPath("$.alternatives.languageScope").value("tamil"))
                .andExpect(jsonPath("$.alternatives.originalLanguage").value("tamil"))
                .andExpect(jsonPath("$.alternatives.leadActors.length()").value(2))
                .andExpect(jsonPath("$.alternatives.leadActors[0].name").value("Star B"))
                .andExpect(jsonPath("$.alternatives.leadActors[0].averageHitProbability").value(0.75))
                .andExpect(jsonPath("$.alternatives.leadActors[0].hitProbabilityGain").value(0.5))
                .andExpect(jsonPath("$.alternatives.leadActors[0].ratingRange").value(1_000))
                .andExpect(jsonPath("$.alternatives.leadActors[0].hitRatio").value(2.0))
                .andExpect(jsonPath("$.alternatives.leadActors[0].similarBudgetFilm.movieName").value("Star B Film"))
                .andExpect(jsonPath("$.alternatives.leadActors[0].similarBudgetFilm.budget").value(80_000_000.0))
                .andExpect(jsonPath("$.alternatives.leadActors[0].similarBudgetFilm.revenue").value(240_000_000.0))
                .andExpect(jsonPath("$.alternatives.leadActors[0].assessments[1].maxBudget").value(160_000.0))
                .andExpect(jsonPath("$.alternatives.leadActors[0].assessments[1].plannedBudget").value(30_000_000.0))
                .andExpect(jsonPath("$.alternatives.leadActors[1].name").value("Star A"))
                .andExpect(jsonPath("$.alternatives.musicDirectors.length()").value(1))
                .andExpect(jsonPath("$.alternatives.musicDirectors[0].name").value("Tunes E"));

        // 30M INR is 375k USD: the band is half to twice that, for films that earned 2x. For a future
        // release, released films are those out by today; the past year counts as recent. The limit
        // leaves room for the request's own people, who are skipped.
        LocalDate today = LocalDate.now();
        String window = today.minusYears(1) + "|" + today.plusDays(1);
        assertThat(similarBudgetLookups).containsExactly(
                "LEAD_ACTOR|187500.0|750000.0|2.0|tamil|false|" + window + "|10",
                "MUSIC_DIRECTOR|187500.0|750000.0|2.0|tamil|false|" + window + "|9");
        // The request, 4 lead candidates (Broken D's 422 is skipped) and 1 music director.
        assertThat(upstream.getRequestCount()).isEqualTo(6);
    }

    @Test
    void alternatives_forSeveralLanguages_arePanIndiaForTheOriginalLanguage() throws Exception {
        scoreBy(Map.of("Some Actor", 0.25));
        assessWithCrew(" Telugu, hindi, tamil")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alternatives.languageScope").value("pan-india"))
                .andExpect(jsonPath("$.alternatives.originalLanguage").value("telugu"))
                .andExpect(jsonPath("$.alternatives.leadActors").isEmpty())
                .andExpect(jsonPath("$.alternatives.musicDirectors").isEmpty());
        assertThat(similarBudgetLookups).hasSize(2).allMatch(lookup -> lookup.contains("|telugu|true|"));
    }

    @Test
    void alternatives_forAPastReleaseDate_onlyLookAtFilmsBeforeIt() throws Exception {
        scoreBy(Map.of("Some Actor", 0.25));
        assessWithCrew("telugu", "2020-01-01").andExpect(status().isOk());
        String recentSince = LocalDate.now().minusYears(1).toString();
        assertThat(similarBudgetLookups).containsExactly(
                "LEAD_ACTOR|187500.0|750000.0|2.0|telugu|false|" + recentSince + "|2020-01-01|10",
                "MUSIC_DIRECTOR|187500.0|750000.0|2.0|telugu|false|" + recentSince + "|2020-01-01|9");
    }

    @Test
    void alternatives_needABudget() throws Exception {
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(UPSTREAM_RESPONSE_WITH_BREAKOUT));
        mvc().perform(post("/api/predictions/budget-risk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieName\": \"F\", \"releaseDate\": \"2026-12-25\", \"language\": \"tamil\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alternatives").doesNotExist());
        assertThat(similarBudgetLookups).isEmpty();
    }
}
