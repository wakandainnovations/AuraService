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

class AuraMathMarketingProxyControllerTest {

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

        AuraMathMarketingProxyController controller =
                new AuraMathMarketingProxyController(proxyService, props, mapper);
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

    private void enqueue500(String body) {
        upstream.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = upstream.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        return req;
    }

    // ==================================================================
    // Genres
    // ==================================================================

    @Test
    void listGenres_happyPath() throws Exception {
        enqueueJson("{\"totalGenres\":2,\"genres\":[{\"genre\":\"sci-fi\",\"keywordCount\":12}]}");

        mvc.perform(get("/v1/marketing/genre"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGenres").value(2));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/genre");
    }

    @Test
    void genrePotentialViewers_happyPath_passesThroughEmptyArray() throws Exception {
        enqueueJson("{\"genre\":\"thriller\",\"totalViewers\":0,\"viewers\":[]}");

        mvc.perform(get("/v1/marketing/genre/{g}/potential-viewers", "thriller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalViewers").value(0))
                .andExpect(jsonPath("$.viewers").isArray());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/genre/thriller/potential-viewers");
    }

    @Test
    void genreSuperSpreaders_happyPath() throws Exception {
        enqueueJson("{\"genre\":\"sci-fi\",\"totalSpreaders\":1,\"spreaders\":[{}]}");

        mvc.perform(get("/v1/marketing/genre/{g}/super-spreaders", "sci-fi"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/genre/sci-fi/super-spreaders");
    }

    @Test
    void genreChannelStrategy_happyPath() throws Exception {
        enqueueJson("{\"genre\":\"horror\",\"topChannel\":\"YouTube\"}");

        mvc.perform(get("/v1/marketing/genre/{g}/channel-strategy", "horror"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topChannel").value("YouTube"));

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/genre/horror/channel-strategy");
    }

    // ==================================================================
    // Parties
    // ==================================================================

    @Test
    void listParties_happyPath() throws Exception {
        enqueueJson("{\"category\":\"media.politics\",\"totalParties\":1,\"parties\":[{\"name\":\"DMK\"}]}");

        mvc.perform(get("/v1/marketing/party"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalParties").value(1));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/party");
    }

    @Test
    void partyPotentialVoters_urlEncodesSpacesAndAmpersands() throws Exception {
        enqueueJson("{\"party\":\"AIADMK & Allies\",\"totalVoters\":0,\"voters\":[]}");

        mvc.perform(get("/v1/marketing/party/{p}/potential-voters", "AIADMK & Allies"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        // Spaces must be %20 (not '+'); '&' must be %26 so it is not parsed as a query separator.
        assertThat(req.getPath())
                .isEqualTo("/api/marketing/party/AIADMK%20%26%20Allies/potential-voters");
    }

    @Test
    void partySuperSpreaders_happyPath() throws Exception {
        enqueueJson("{\"party\":\"DMK\",\"totalSpreaders\":0,\"spreaders\":[]}");

        mvc.perform(get("/v1/marketing/party/{p}/super-spreaders", "DMK"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/party/DMK/super-spreaders");
    }

    @Test
    void partyChannelStrategy_happyPath() throws Exception {
        enqueueJson("{\"party\":\"DMK\",\"topChannel\":\"X\"}");

        mvc.perform(get("/v1/marketing/party/{p}/channel-strategy", "DMK"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/party/DMK/channel-strategy");
    }

    @Test
    void partyPotentialVoters_nonAsciiPathParamIsUrlEncoded() throws Exception {
        enqueueJson("{\"party\":\"திமுக\",\"totalVoters\":0,\"voters\":[]}");

        mvc.perform(get("/v1/marketing/party/{p}/potential-voters", "திமுக"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        // Tamil "திமுக" → 5 UTF-8 codepoints, each one 3 bytes percent-encoded.
        assertThat(req.getPath())
                .isEqualTo("/api/marketing/party/%E0%AE%A4%E0%AE%BF%E0%AE%AE%E0%AF%81%E0%AE%95/potential-voters");
    }

    // ==================================================================
    // Celebrities
    // ==================================================================

    @Test
    void listCelebrities_happyPath() throws Exception {
        enqueueJson("{\"category\":\"media.celebrity\",\"totalCelebrities\":1,\"celebrities\":[{\"name\":\"Rajinikanth\"}]}");

        mvc.perform(get("/v1/marketing/celebrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCelebrities").value(1));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/celebrity");
    }

    @Test
    void celebrityPotentialFans_happyPath() throws Exception {
        enqueueJson("{\"celebrity\":\"Rajinikanth\",\"totalFans\":0,\"fans\":[]}");

        mvc.perform(get("/v1/marketing/celebrity/{c}/potential-fans", "Rajinikanth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFans").value(0));

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/celebrity/Rajinikanth/potential-fans");
    }

    @Test
    void celebritySuperFans_happyPath() throws Exception {
        enqueueJson("{\"celebrity\":\"Rajinikanth\",\"totalSuperFans\":0,\"superFans\":[]}");

        mvc.perform(get("/v1/marketing/celebrity/{c}/super-fans", "Rajinikanth"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/celebrity/Rajinikanth/super-fans");
    }

    @Test
    void celebrityChannelStrategy_urlEncodesSpaces() throws Exception {
        enqueueJson("{\"celebrity\":\"A R Rahman\",\"topChannel\":\"Instagram\"}");

        mvc.perform(get("/v1/marketing/celebrity/{c}/channel-strategy", "A R Rahman"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/celebrity/A%20R%20Rahman/channel-strategy");
    }

    // ==================================================================
    // Error mapping: upstream 500 → sanitized 502
    // ==================================================================

    @Test
    void upstream500_isMappedToSanitized502_andDoesNotLeakSqlFragments() throws Exception {
        // Body shape mirrors Spring Boot's default error envelope upstream returns.
        String upstreamBody = "{"
                + "\"timestamp\":\"2026-05-17T10:00:00.000+00:00\","
                + "\"status\":500,"
                + "\"error\":\"Internal Server Error\","
                + "\"message\":\"PSQLException: ERROR: relation \\\"posts\\\" does not exist; nested SQL: SELECT * FROM posts WHERE keyword ILIKE 'DMK'\","
                + "\"path\":\"/api/marketing/party/DMK/potential-voters\""
                + "}";
        enqueue500(upstreamBody);

        mvc.perform(get("/v1/marketing/party/{p}/potential-voters", "DMK"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_failure"))
                .andExpect(jsonPath("$.upstream_path")
                        .value("/api/marketing/party/DMK/potential-voters"))
                // The sanitized body must NOT echo the upstream message/SQL fragments.
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.upstreamBody").doesNotExist());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/party/DMK/potential-voters");
    }

    @Test
    void upstream500_onChannelStrategy_isMappedToSanitized502() throws Exception {
        enqueue500("{\"status\":500,\"message\":\"boom\",\"path\":\"/api/marketing/celebrity/x/channel-strategy\"}");

        mvc.perform(get("/v1/marketing/celebrity/{c}/channel-strategy", "x"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_failure"))
                .andExpect(jsonPath("$.upstream_path")
                        .value("/api/marketing/celebrity/x/channel-strategy"));
    }

    // ==================================================================
    // Caching
    // ==================================================================

    @Test
    void cachedGet_doesNotCallUpstreamSecondTime() throws Exception {
        enqueueJson("{\"genre\":\"drama\",\"totalViewers\":0,\"viewers\":[]}");

        mvc.perform(get("/v1/marketing/genre/{g}/potential-viewers", "drama"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/marketing/genre/{g}/potential-viewers", "drama"))
                .andExpect(status().isOk());

        assertThat(upstream.getRequestCount()).isEqualTo(1);
    }

    // ==================================================================
    // _catalog discovery
    // ==================================================================

    @Test
    void catalog_listsAllRoutes_withoutCallingUpstream() throws Exception {
        mvc.perform(get("/v1/marketing/_catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRoutes").value(19))
                .andExpect(jsonPath("$.routes[0].wrapperPath").value("/v1/marketing/genre"))
                .andExpect(jsonPath("$.routes[0].upstreamPath").value("/api/marketing/genre"))
                .andExpect(jsonPath("$.routes[12].wrapperPath").value("/v1/marketing/entity-report/{entityId}"))
                .andExpect(jsonPath("$.routes[13].wrapperPath").value("/v1/marketing/entity/{entityId}/report"))
                .andExpect(jsonPath("$.routes[14].wrapperPath").value("/v1/marketing/language/{language}/users"))
                .andExpect(jsonPath("$.routes[15].wrapperPath").value("/v1/marketing/language/{language}/movie/{movieName}/users"))
                .andExpect(jsonPath("$.routes[16].wrapperPath").value("/v1/marketing/movie-buffs/{keyword}"))
                .andExpect(jsonPath("$.routes[17].wrapperPath").value("/v1/marketing/narrative-novelty/score"))
                .andExpect(jsonPath("$.routes[18].wrapperPath").value("/v1/marketing/narrative-novelty/lookup"));

        assertThat(upstream.getRequestCount()).isZero();
    }

    // ==================================================================
    // Language-affinity audiences
    // ==================================================================

    @Test
    void languageUsers_happyPath() throws Exception {
        enqueueJson("{\"language\":\"Tamil\",\"totalUsers\":1,\"users\":[{}]}");

        mvc.perform(get("/v1/marketing/language/{l}/users", "Tamil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(1));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/language/Tamil/users");
    }

    @Test
    void languageMovieUsers_happyPath_urlEncodesMovieName() throws Exception {
        enqueueJson("{\"language\":\"Tamil\",\"movie\":\"Vikram\",\"totalUsers\":0,\"users\":[]}");

        mvc.perform(get("/v1/marketing/language/{l}/movie/{m}/users", "Tamil", "Vikram 2"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/language/Tamil/movie/Vikram%202/users");
    }

    // ==================================================================
    // Movie buffs
    // ==================================================================

    @Test
    void movieBuffs_happyPath() throws Exception {
        enqueueJson("{\"keyword\":\"Avengers\",\"totalMovieBuffs\":0,\"movieBuffs\":[]}");

        mvc.perform(get("/v1/marketing/movie-buffs/{k}", "Avengers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMovieBuffs").value(0));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/movie-buffs/Avengers");
    }

    // ==================================================================
    // Narrative novelty
    // ==================================================================

    @Test
    void narrativeNoveltyScore_happyPath_forwardsBody() throws Exception {
        enqueueJson("{\"movieName\":\"Untitled\",\"score\":0.4}");

        mvc.perform(post("/v1/marketing/narrative-novelty/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"synopsis\":\"A detective races to...\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0.4));

        RecordedRequest req = takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/marketing/narrative-novelty/score");
        assertThat(req.getBody().readUtf8()).contains("A detective races to");
    }

    @Test
    void narrativeNoveltyScore_upstream400_isRelayedVerbatim() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"synopsis is required\"}"));

        mvc.perform(post("/v1/marketing/narrative-novelty/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("synopsis is required"));
    }

    @Test
    void narrativeNoveltyLookup_happyPath_encodesMovieNameQueryParam() throws Exception {
        enqueueJson("{\"movieName\":\"The Silent Ledger\",\"score\":0.4}");

        mvc.perform(get("/v1/marketing/narrative-novelty/lookup").param("movieName", "The Silent Ledger"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath())
                .isEqualTo("/api/marketing/narrative-novelty/lookup?movieName=The+Silent+Ledger");
    }
}
