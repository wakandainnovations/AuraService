package com.aura.service.controller;

import com.aura.service.entity.EntityKeyword;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.proxy.AuraMathClientConfig;
import com.aura.service.proxy.AuraMathProperties;
import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.EntitlementService;
import com.aura.service.service.EntitlementServiceImpl;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.LicenseService;
import com.aura.service.service.MarketingAggregationService;
import com.aura.service.service.PreviewMaskingServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketingAggregationControllerTest {

    private MockWebServer upstream;
    private MockMvc mvc;
    private ManagedEntityRepository entityRepository;
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

        entityRepository = mock(ManagedEntityRepository.class);
        MarketingAggregationService service = new MarketingAggregationService(
                entityRepository, proxyService, mapper);
        EntityAccessService entityAccess = mock(EntityAccessService.class);
        when(entityAccess.currentUserIsAdmin()).thenReturn(true);
        EntitlementService entitlement = new EntitlementServiceImpl(
                mock(LicenseService.class), entityAccess, new PreviewMaskingServiceImpl());
        MarketingAggregationController controller = new MarketingAggregationController(
                service, entityAccess, entitlement);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
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
    // Validation: at least one filter required
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_noFilter_returns400() throws Exception {
        mvc.perform(get("/api/marketing/aggregate/top-spreaders"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Top Spreaders — flat (default)
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_flat_mergesAcrossKeywords() throws Exception {
        when(entityRepository.findKeywordsByFilters("Tamil", null, null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("karuppu", "media.movie", "Tamil", null, null, null),
                        new EntityKeyword("surya", "media.celebrity", "Tamil", null, "Kollywood", null)
                ));

        enqueueJson("[{\"author\":\"user1\",\"primaryPlatform\":\"twitter\",\"influenceTier\":\"mega\"},"
                + "{\"author\":\"user2\",\"primaryPlatform\":\"instagram\",\"influenceTier\":\"macro\"}]");
        enqueueJson("[{\"author\":\"user2\",\"primaryPlatform\":\"instagram\",\"influenceTier\":\"macro\"},"
                + "{\"author\":\"user3\",\"primaryPlatform\":\"youtube\",\"influenceTier\":\"micro\"}]");

        mvc.perform(get("/api/marketing/aggregate/top-spreaders")
                        .param("language", "Tamil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].author").value("user1"))
                .andExpect(jsonPath("$.data[1].author").value("user2"))
                .andExpect(jsonPath("$.data[2].author").value("user3"));

        RecordedRequest req1 = takeRequest();
        assertThat(req1.getPath()).contains("top-50-spreaders/karuppu");
        RecordedRequest req2 = takeRequest();
        assertThat(req2.getPath()).contains("top-50-spreaders/surya");
    }

    // ------------------------------------------------------------------
    // Top Spreaders — grouped by keyword
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_groupedByKeyword() throws Exception {
        when(entityRepository.findKeywordsByFilters(null, "Tollywood", null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("baahubali", "media.movie", "Telugu", null, "Tollywood", null)
                ));

        enqueueJson("[{\"author\":\"spread1\",\"primaryPlatform\":\"twitter\"}]");

        String response = mvc.perform(get("/api/marketing/aggregate/top-spreaders")
                        .param("industry", "Tollywood")
                        .param("groupBy", "keyword"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = mapper.readTree(response).get("data");
        assertThat(data.has("baahubali")).isTrue();
        assertThat(data.get("baahubali").isArray()).isTrue();
        assertThat(data.get("baahubali").get(0).get("author").asText()).isEqualTo("spread1");
    }

    // ------------------------------------------------------------------
    // Genre filter — case-insensitive matching (regression)
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_genreFilter_isCaseInsensitive() throws Exception {
        // Entities store genres verbatim (e.g. "Drama"), but callers filter using any
        // case. The genre LIKE pattern must therefore be lower-cased so it matches the
        // LOWER(genre) comparison in the query; otherwise genre=drama returns empty.
        ArgumentCaptor<String> genrePattern = ArgumentCaptor.forClass(String.class);
        when(entityRepository.findKeywordsByFilters(any(), any(), any(), genrePattern.capture(), any()))
                .thenReturn(List.of(
                        new EntityKeyword("surya-movie", "media.movie", "Tamil", null, null, "Drama")
                ));

        enqueueJson("[{\"author\":\"fan1\",\"primaryPlatform\":\"twitter\"}]");

        mvc.perform(get("/api/marketing/aggregate/top-spreaders")
                        .param("genre", "Drama"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].author").value("fan1"));

        assertThat(genrePattern.getValue()).isEqualTo("%,drama,%");

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).contains("top-50-spreaders/surya-movie");
    }

    // ------------------------------------------------------------------
    // Viral Seeds
    // ------------------------------------------------------------------
    @Test
    void viralSeeds_flat() throws Exception {
        when(entityRepository.findKeywordsByFilters("Tamil", null, null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("karuppu", "media.movie", "Tamil", null, null, null)
                ));

        enqueueJson("[{\"userId\":\"seed1\"},{\"userId\":\"seed2\"}]");

        mvc.perform(get("/api/marketing/aggregate/viral-seeds")
                        .param("language", "Tamil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).contains("viral-seeds");
        assertThat(req.getPath()).contains("keyword=karuppu");
    }

    // ------------------------------------------------------------------
    // Aspect Drivers
    // ------------------------------------------------------------------
    @Test
    void aspectDrivers_flat() throws Exception {
        when(entityRepository.findKeywordsByFilters(null, null, null, null, 1L))
                .thenReturn(List.of(
                        new EntityKeyword("movie1", "media.movie", "Tamil", null, null, null)
                ));

        // Upstream /api/marketing/aspect-drivers/{keyword} returns a JSON object
        // (not an array); the flat aggregation must include it as a single element.
        enqueueJson("{\"keyword\":\"movie1\",\"id\":\"driver1\"}");

        mvc.perform(get("/api/marketing/aggregate/aspect-drivers")
                        .param("entityId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("driver1"));

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).contains("aspect-drivers/movie1");
    }

    // ------------------------------------------------------------------
    // Brand Evangelists
    // ------------------------------------------------------------------
    @Test
    void brandEvangelists_flat() throws Exception {
        when(entityRepository.findKeywordsByFilters("Telugu", null, null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("rrr", "media.movie", "Telugu", null, null, null)
                ));

        enqueueJson("[{\"author\":\"evangelist1\"},{\"author\":\"evangelist2\"}]");

        mvc.perform(get("/api/marketing/aggregate/brand-evangelists")
                        .param("language", "Telugu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).contains("brand-evangelists/rrr");
    }

    // ------------------------------------------------------------------
    // Genre aggregation
    // ------------------------------------------------------------------
    @Test
    void genre_potentialViewers_flat() throws Exception {
        when(entityRepository.findKeywordsByFilters("Tamil", null, null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("karuppu", "media.movie", "Tamil", null, null, "action"),
                        new EntityKeyword("surya-movie", "media.movie", "Tamil", null, null, "drama")
                ));

        enqueueJson("[{\"userId\":\"viewer1\"},{\"userId\":\"viewer2\"}]");
        enqueueJson("[{\"userId\":\"viewer2\"},{\"userId\":\"viewer3\"}]");

        mvc.perform(get("/api/marketing/aggregate/genre/potential-viewers")
                        .param("language", "Tamil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        RecordedRequest req1 = takeRequest();
        assertThat(req1.getPath()).contains("genre/action/potential-viewers");
        RecordedRequest req2 = takeRequest();
        assertThat(req2.getPath()).contains("genre/drama/potential-viewers");
    }

    @Test
    void genre_objectResponses_flat_dedupesIdenticalObjects() throws Exception {
        when(entityRepository.findKeywordsByFilters("Tamil", null, null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("karuppu", "media.movie", "Tamil", null, null, "action"),
                        new EntityKeyword("surya-movie", "media.movie", "Tamil", null, null, "drama")
                ));

        // Object (non-array) upstream responses: identical object from both genres
        // must collapse to a single element, mirroring the array-branch dedup.
        enqueueJson("{\"userId\":\"viewer1\",\"score\":9}");
        enqueueJson("{\"userId\":\"viewer1\",\"score\":9}");

        mvc.perform(get("/api/marketing/aggregate/genre/channel-strategy")
                        .param("language", "Tamil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value("viewer1"));

        RecordedRequest req1 = takeRequest();
        assertThat(req1.getPath()).contains("genre/action/channel-strategy");
        RecordedRequest req2 = takeRequest();
        assertThat(req2.getPath()).contains("genre/drama/channel-strategy");
    }

    @Test
    void genre_superSpreaders_groupedByGenre() throws Exception {
        when(entityRepository.findKeywordsByFilters(null, "Kollywood", null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("movie1", "media.movie", "Tamil", null, "Kollywood", "thriller")
                ));

        enqueueJson("[{\"author\":\"spreader1\"}]");

        String response = mvc.perform(get("/api/marketing/aggregate/genre/super-spreaders")
                        .param("industry", "Kollywood")
                        .param("groupBy", "genre"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = mapper.readTree(response).get("data");
        assertThat(data.has("thriller")).isTrue();
        assertThat(data.get("thriller").isArray()).isTrue();
    }

    @Test
    void genre_invalidSubType_returns400() throws Exception {
        mvc.perform(get("/api/marketing/aggregate/genre/invalid-type")
                        .param("language", "Tamil"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ------------------------------------------------------------------
    // Empty results
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_noMatchingKeywords_returnsEmptyList() throws Exception {
        when(entityRepository.findKeywordsByFilters("Klingon", null, null, null, null))
                .thenReturn(List.of());

        mvc.perform(get("/api/marketing/aggregate/top-spreaders")
                        .param("language", "Klingon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void topSpreaders_noMatchingKeywords_grouped_returnsEmptyMap() throws Exception {
        when(entityRepository.findKeywordsByFilters("Klingon", null, null, null, null))
                .thenReturn(List.of());

        String response = mvc.perform(get("/api/marketing/aggregate/top-spreaders")
                        .param("language", "Klingon")
                        .param("groupBy", "keyword"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = mapper.readTree(response).get("data");
        assertThat(data.isObject()).isTrue();
        assertThat(data.isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------
    // Upstream failure handling
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_upstreamFailure_skipsFailedKeyword() throws Exception {
        when(entityRepository.findKeywordsByFilters("Tamil", null, null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("good", "media.movie", "Tamil", null, null, null),
                        new EntityKeyword("bad", "media.movie", "Tamil", null, null, null)
                ));

        enqueueJson("[{\"author\":\"user1\"}]");
        upstream.enqueue(new MockResponse().setResponseCode(500).setBody("error"));

        mvc.perform(get("/api/marketing/aggregate/top-spreaders")
                        .param("language", "Tamil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].author").value("user1"));
    }

    // ------------------------------------------------------------------
    // Deduplication across keywords
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_deduplicatesAcrossKeywords() throws Exception {
        when(entityRepository.findKeywordsByFilters("Tamil", null, null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("kw1", "media.movie", "Tamil", null, null, null),
                        new EntityKeyword("kw2", "media.movie", "Tamil", null, null, null)
                ));

        enqueueJson("[{\"author\":\"shared\",\"primaryPlatform\":\"twitter\"},"
                + "{\"author\":\"unique1\",\"primaryPlatform\":\"instagram\"}]");
        enqueueJson("[{\"author\":\"shared\",\"primaryPlatform\":\"twitter\"},"
                + "{\"author\":\"unique2\",\"primaryPlatform\":\"youtube\"}]");

        mvc.perform(get("/api/marketing/aggregate/top-spreaders")
                        .param("language", "Tamil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    // ------------------------------------------------------------------
    // Multiple filters
    // ------------------------------------------------------------------
    @Test
    void topSpreaders_multipleFilters() throws Exception {
        when(entityRepository.findKeywordsByFilters("Tamil", "Kollywood", null, null, null))
                .thenReturn(List.of(
                        new EntityKeyword("movie1", "media.movie", "Tamil", null, "Kollywood", null)
                ));

        enqueueJson("[{\"author\":\"user1\"}]");

        mvc.perform(get("/api/marketing/aggregate/top-spreaders")
                        .param("language", "Tamil")
                        .param("industry", "Kollywood"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}
