package com.aura.service.controller;

import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.proxy.AuraMathClientConfig;
import com.aura.service.proxy.AuraMathProperties;
import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.service.EntityAccessService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link EntityCausalIntelController} against a real {@link AuraMathProxyService} backed
 * by a {@link MockWebServer} (same style as {@code MarketingAggregationControllerTest}), with only
 * the true interface dependency ({@link EntityAccessService}) mocked. This exercises the actual
 * forwardGet + TtlCache behavior rather than stubbing it away.
 */
class EntityCausalIntelControllerTest {

    private MockWebServer upstream;
    private MockMvc mvc;
    private EntityAccessService entityAccessService;
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

        entityAccessService = mock(EntityAccessService.class);
        EntityCausalIntelController controller =
                new EntityCausalIntelController(proxyService, props, entityAccessService);
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
    // Ownership enforced before any upstream call is made
    // ------------------------------------------------------------------

    @Test
    void vmi_notOwned_returns404_neverCallsUpstream() throws Exception {
        when(entityAccessService.assertOwnedByCurrentUser(42L))
                .thenThrow(new ResourceNotFoundException("not found"));

        mvc.perform(get("/api/entities/42/vmi"))
                .andExpect(status().isNotFound());

        assertThat(upstream.getRequestCount()).isEqualTo(0);
    }

    @Test
    void causalChains_notOwned_returns404_neverCallsUpstream() throws Exception {
        when(entityAccessService.assertOwnedByCurrentUser(7L))
                .thenThrow(new ResourceNotFoundException("not found"));

        mvc.perform(get("/api/entities/7/causal-chains"))
                .andExpect(status().isNotFound());

        assertThat(upstream.getRequestCount()).isEqualTo(0);
    }

    @Test
    void nonobviousLevers_notOwned_returns404_neverCallsUpstream() throws Exception {
        when(entityAccessService.assertOwnedByCurrentUser(9L))
                .thenThrow(new ResourceNotFoundException("not found"));

        mvc.perform(get("/api/entities/9/nonobvious-levers"))
                .andExpect(status().isNotFound());

        assertThat(upstream.getRequestCount()).isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // Owned entity: forwards to the right upstream path; insufficient_history passes through
    // unmodified (not collapsed into an empty array).
    // ------------------------------------------------------------------

    @Test
    void vmi_owned_forwardsInsufficientHistoryBodyVerbatim() throws Exception {
        enqueueJson("{\"status\":\"insufficient_history\",\"details\":\"no rows yet\"}");

        mvc.perform(get("/api/entities/1/vmi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("insufficient_history"))
                .andExpect(jsonPath("$.details").value("no rows yet"))
                .andExpect(jsonPath("$.series").doesNotExist());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).contains("/api/marketing/entity/1/vmi");
    }

    @Test
    void causalChains_owned_forwardsToCorrectUpstreamPath() throws Exception {
        enqueueJson("{\"status\":\"ok\",\"chains\":[]}");

        mvc.perform(get("/api/entities/3/causal-chains"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).contains("/api/marketing/entity/3/causal-chains");
    }

    @Test
    void nonobviousLevers_owned_forwardsToCorrectUpstreamPath() throws Exception {
        enqueueJson("{\"status\":\"ok\",\"findings\":[]}");

        mvc.perform(get("/api/entities/5/nonobvious-levers"))
                .andExpect(status().isOk());

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).contains("/api/marketing/entity/5/nonobvious-levers");
    }

    // ------------------------------------------------------------------
    // Ownership is re-checked on every request, even when the body is served from the TtlCache
    // ------------------------------------------------------------------

    @Test
    void vmi_secondRequest_servedFromCache_stillEnforcesOwnershipEachTime() throws Exception {
        enqueueJson("{\"status\":\"ok\",\"series\":[]}");

        mvc.perform(get("/api/entities/8/vmi")).andExpect(status().isOk());
        mvc.perform(get("/api/entities/8/vmi")).andExpect(status().isOk());

        // Only one upstream call - the second request was served from AuraMathProxyService's cache.
        assertThat(upstream.getRequestCount()).isEqualTo(1);
        // But ownership must be verified on BOTH requests, never skipped once a cached body exists.
        verify(entityAccessService, times(2)).assertOwnedByCurrentUser(8L);
    }

    @Test
    void vmi_ownershipRevokedBetweenRequests_secondRequestStill404sDespiteWarmCache() throws Exception {
        enqueueJson("{\"status\":\"ok\",\"series\":[]}");
        when(entityAccessService.assertOwnedByCurrentUser(11L))
                .thenReturn(null)
                .thenThrow(new ResourceNotFoundException("not found"));

        mvc.perform(get("/api/entities/11/vmi")).andExpect(status().isOk());
        // Even though a cached body now exists for this URL, the ownership check runs first and
        // must still gate access - a cache hit must never bypass it.
        mvc.perform(get("/api/entities/11/vmi")).andExpect(status().isNotFound());
    }
}
