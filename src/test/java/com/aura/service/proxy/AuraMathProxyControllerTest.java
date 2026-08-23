package com.aura.service.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuraMathProxyControllerTest {

    private MockWebServer upstream;
    private MockMvc mvc;
    private AuraMathProxyService proxyService;
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
        proxyService = new AuraMathProxyService(
                config.auraMathWebClient(props, provider),
                config.auraMathSyncWebClient(props, provider),
                props,
                mapper
        );

        AuraMathProxyController controller = new AuraMathProxyController(proxyService, props);
        HealthController health = new HealthController(proxyService);
        mvc = MockMvcBuilders.standaloneSetup(controller, health).build();
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

    // ------------------------------------------------------------------
    // 1. /v1/viral-seeds
    // ------------------------------------------------------------------
    @Test
    void viralSeeds_happyPath() throws Exception {
        enqueueJson("[{\"seedId\":\"s1\"}]");

        mvc.perform(get("/v1/viral-seeds").param("keyword", "fantasy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seedId").value("s1"));

        RecordedRequest req = takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/api/marketing/viral-seeds?keyword=fantasy");
    }

    // ------------------------------------------------------------------
    // 2. /v1/aspect-drivers/{keyword}
    // ------------------------------------------------------------------
    @Test
    void aspectDrivers_happyPath() throws Exception {
        enqueueJson("{\"keyword\":\"drama\"}");

        mvc.perform(get("/v1/aspect-drivers/{k}", "drama"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyword").value("drama"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/aspect-drivers/drama");
    }

    // ------------------------------------------------------------------
    // 2a. /v1/aspect-drivers?entityId= — entity-scoped variant
    // ------------------------------------------------------------------
    @Test
    void aspectDriversByEntity_happyPath() throws Exception {
        enqueueJson("{\"entityId\":\"29\",\"name\":\"Madhavan\"}");

        mvc.perform(get("/v1/aspect-drivers").param("entityId", "29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("29"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/aspect-drivers?entityId=29");
    }

    // ------------------------------------------------------------------
    // 3. /v1/top-spreaders/{keyword}
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_happyPath() throws Exception {
        enqueueJson("[\"alice\",\"bob\"]");

        mvc.perform(get("/v1/top-spreaders/{k}", "comedy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("alice"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/top-50-spreaders/comedy");
    }

    // ------------------------------------------------------------------
    // 3a. /v1/top-spreaders/{keyword}?platform= — happy path, platform forwarded lowercase
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_withPlatform_forwardsLowercasedPlatform() throws Exception {
        enqueueJson("[\"alice\"]");

        mvc.perform(get("/v1/top-spreaders/{k}", "comedy").param("platform", "YouTube"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/marketing/top-50-spreaders/comedy?platform=youtube");
    }

    // ------------------------------------------------------------------
    // 3b. /v1/top-spreaders/{keyword}?platform= — invalid platform rejected without upstream call
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_invalidPlatform_returns400_withoutUpstreamCall() throws Exception {
        mvc.perform(get("/v1/top-spreaders/{k}", "comedy").param("platform", "tiktok"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        assertThat(upstream.getRequestCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 4. POST /v1/find-lookalikes — happy path
    // ------------------------------------------------------------------
    @Test
    void findLookalikes_happyPath() throws Exception {
        enqueueJson("[{\"authorId\":\"X\"}]");

        mvc.perform(post("/v1/find-lookalikes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seedAuthorId\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authorId").value("X"));

        RecordedRequest req = takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/marketing/find-lookalikes");
        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        assertThat(body.get("seedAuthorId").asText()).isEqualTo("alice");
    }

    // ------------------------------------------------------------------
    // POST /v1/find-lookalikes — 400 when seedAuthorId missing (no upstream call)
    // ------------------------------------------------------------------
    @Test
    void findLookalikes_missingSeedAuthorId_returns400_withoutUpstreamCall() throws Exception {
        mvc.perform(post("/v1/find-lookalikes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(post("/v1/find-lookalikes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seedAuthorId\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertThat(upstream.getRequestCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 4a. GET /v1/find-lookalikes/diff — happy path, limit omitted
    // ------------------------------------------------------------------
    @Test
    void findLookalikesDiff_happyPath_omitsLimitWhenNotProvided() throws Exception {
        enqueueJson("{\"seedAuthorId\":\"alice\",\"overlap_count\":3}");

        mvc.perform(get("/v1/find-lookalikes/diff").param("seedAuthorId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overlap_count").value(3));

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/marketing/find-lookalikes/diff?seedAuthorId=alice");
    }

    @Test
    void findLookalikesDiff_withLimit_passesThroughQueryParam() throws Exception {
        enqueueJson("{\"seedAuthorId\":\"alice\",\"limit\":10}");

        mvc.perform(get("/v1/find-lookalikes/diff")
                        .param("seedAuthorId", "alice")
                        .param("limit", "10"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).contains("seedAuthorId=alice");
        assertThat(req.getPath()).contains("limit=10");
    }

    // ------------------------------------------------------------------
    // 5. /v1/users/{globalUserId}/profile
    // ------------------------------------------------------------------
    @Test
    void userProfile_happyPath() throws Exception {
        enqueueJson("{\"globalUserId\":\"u1\"}");

        mvc.perform(get("/v1/users/{id}/profile", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.globalUserId").value("u1"));

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/user-profile/u1");
    }

    // ------------------------------------------------------------------
    // 6. /v1/users/{author}/report — NOT cacheable
    // ------------------------------------------------------------------
    @Test
    void userReport_notCached_callsUpstreamEveryTime() throws Exception {
        enqueueJson("{\"author\":\"alice\",\"call\":1}");
        enqueueJson("{\"author\":\"alice\",\"call\":2}");

        mvc.perform(get("/v1/users/{a}/report", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.call").value(1));
        mvc.perform(get("/v1/users/{a}/report", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.call").value(2));

        assertThat(upstream.getRequestCount()).isEqualTo(2);
        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/user-report/alice");
        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/user-report/alice");
    }

    // ------------------------------------------------------------------
    // 6a. /v1/users/{author}/report — author with a space is single-encoded.
    // Regression: the proxy used to route the already-encoded segment through
    // UriBuilder.path(), turning the '%' of "%20" into "%25" and sending the
    // upstream a doubly-encoded "News7%2520Tamil" (which it read as the literal
    // text "News7%20Tamil" → "No post history found").
    // ------------------------------------------------------------------
    @Test
    void userReport_authorWithSpace_isSingleEncodedNotDoubleEncoded() throws Exception {
        enqueueJson("{\"author\":\"News7 Tamil\"}");

        mvc.perform(get("/v1/users/{a}/report", "News7 Tamil"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/marketing/user-report/News7%20Tamil");
        assertThat(req.getPath()).doesNotContain("%2520");
    }

    // ------------------------------------------------------------------
    // 6b. /v1/users/{author}/report — non-ASCII author is percent-encoded once.
    // ------------------------------------------------------------------
    @Test
    void userReport_authorWithNonAscii_isSingleEncoded() throws Exception {
        enqueueJson("{\"author\":\"Niño\"}");

        mvc.perform(get("/v1/users/{a}/report", "Niño"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/marketing/user-report/Ni%C3%B1o");
        assertThat(req.getPath()).doesNotContain("%25");
    }

    // ------------------------------------------------------------------
    // 7. /v1/users — with optional filters
    // ------------------------------------------------------------------
    @Test
    void users_happyPath_passesFilters() throws Exception {
        enqueueJson("[]");

        mvc.perform(get("/v1/users")
                        .param("audienceClassification", "GenZ")
                        .param("influenceTier", "TIER_1")
                        .param("primaryPlatform", "TWITTER"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).startsWith("/api/marketing/users?");
        assertThat(req.getPath()).contains("audienceClassification=GenZ");
        assertThat(req.getPath()).contains("influenceTier=TIER_1");
        assertThat(req.getPath()).contains("primaryPlatform=TWITTER");
    }

    // ------------------------------------------------------------------
    // 7a. /v1/users — a filter value containing a space is encoded in the query
    // string (and not left raw / double-encoded).
    // ------------------------------------------------------------------
    @Test
    void users_filterValueWithSpace_isEncodedInQuery() throws Exception {
        enqueueJson("[]");

        mvc.perform(get("/v1/users").param("audienceClassification", "Gen Z"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/marketing/users?audienceClassification=Gen+Z");
    }

    // ------------------------------------------------------------------
    // 8. /v1/users/categories — cacheable
    // ------------------------------------------------------------------
    @Test
    void userCategories_cacheHit_doesNotCallUpstreamSecondTime() throws Exception {
        enqueueJson("[\"A\",\"B\"]");

        mvc.perform(get("/v1/users/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1]").value("B"));
        mvc.perform(get("/v1/users/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1]").value("B"));

        assertThat(upstream.getRequestCount()).isEqualTo(1);
        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/users/categories");
    }

    // ------------------------------------------------------------------
    // 9. /v1/users/sync — POST, NOT cacheable, no body
    // ------------------------------------------------------------------
    @Test
    void userSync_happyPath() throws Exception {
        enqueueJson("{\"synced\":true}");

        mvc.perform(post("/v1/users/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.synced").value(true));

        RecordedRequest req = takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/marketing/users/sync");
    }

    // ------------------------------------------------------------------
    // 10. /v1/genres/{genre}/potential-viewers
    // ------------------------------------------------------------------
    @Test
    void potentialViewers_happyPath() throws Exception {
        enqueueJson("[]");

        mvc.perform(get("/v1/genres/{g}/potential-viewers", "thriller"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/genre/thriller/potential-viewers");
    }

    // ------------------------------------------------------------------
    // 11. /v1/genres/{genre}/super-spreaders
    // ------------------------------------------------------------------
    @Test
    void superSpreaders_happyPath() throws Exception {
        enqueueJson("[]");

        mvc.perform(get("/v1/genres/{g}/super-spreaders", "sci-fi"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/genre/sci-fi/super-spreaders");
    }

    // ------------------------------------------------------------------
    // 12. /v1/genres/{genre}/channel-strategy
    // ------------------------------------------------------------------
    @Test
    void channelStrategy_happyPath() throws Exception {
        enqueueJson("{}");

        mvc.perform(get("/v1/genres/{g}/channel-strategy", "horror"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/marketing/genre/horror/channel-strategy");
    }

    // ------------------------------------------------------------------
    // 13. /v1/targets — query params with default minInfluenceScore
    // ------------------------------------------------------------------
    @Test
    void targets_happyPath_appliesDefaultMinInfluenceScore() throws Exception {
        enqueueJson("[]");

        mvc.perform(get("/v1/targets").param("genre", "drama").param("platform", "TIKTOK"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).startsWith("/v1/targets?");
        assertThat(req.getPath()).contains("genre=drama");
        assertThat(req.getPath()).contains("minInfluenceScore=0.0");
        assertThat(req.getPath()).contains("platform=TIKTOK");
    }

    // ------------------------------------------------------------------
    // 14. /v1/diagnostics/raw-mapping/{author}
    // ------------------------------------------------------------------
    @Test
    void rawMapping_happyPath() throws Exception {
        enqueueJson("{\"author\":\"x\"}");

        mvc.perform(get("/v1/diagnostics/raw-mapping/{a}", "x"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/test/raw-mapping/x");
    }

    // ------------------------------------------------------------------
    // 15. /v1/diagnostics/temporal-audit/{author}
    // ------------------------------------------------------------------
    @Test
    void temporalAudit_happyPath() throws Exception {
        enqueueJson("{\"author\":\"x\"}");

        mvc.perform(get("/v1/diagnostics/temporal-audit/{a}", "x"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/api/test/temporal-audit/x");
    }

    // ------------------------------------------------------------------
    // 16. /v1/diagnostics/process-user/{author} — upstream path is /test/..., NOT /api/test/...
    // ------------------------------------------------------------------
    @Test
    void processUser_happyPath_usesTestPathNotApiTestPath() throws Exception {
        enqueueJson("{\"processed\":true}");

        mvc.perform(get("/v1/diagnostics/process-user/{a}", "alice"))
                .andExpect(status().isOk());

        assertThat(takeRequest().getPath()).isEqualTo("/test/process-user/alice");
    }

    // ------------------------------------------------------------------
    // /healthz
    // ------------------------------------------------------------------
    @Test
    void healthz_returns200_whenUpstreamReachable() throws Exception {
        enqueueJson("[]");

        mvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).startsWith("/v1/targets?");
        assertThat(req.getPath()).contains("minInfluenceScore=999999");
    }

    // ------------------------------------------------------------------
    // Non-2xx upstream — wrapped as { upstreamStatus, upstreamBody }
    // ------------------------------------------------------------------
    @Test
    void nonTwoXxUpstream_isWrapped() throws Exception {
        upstream.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"err\":\"not found\"}"));

        mvc.perform(get("/v1/aspect-drivers/{k}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.upstreamStatus").value(404))
                .andExpect(jsonPath("$.upstreamBody.err").value("not found"));
    }
}
