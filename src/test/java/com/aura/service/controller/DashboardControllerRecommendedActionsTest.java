package com.aura.service.controller;

import com.aura.service.dto.RecommendedActionItem;
import com.aura.service.dto.RecommendedActionsResponse;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.enums.RecommendedActionStatus;
import com.aura.service.service.EntitlementServiceImpl;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.LicenseService;
import com.aura.service.service.PreviewMaskingServiceImpl;
import com.aura.service.service.RecommendedActionsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardControllerRecommendedActionsTest {

    private static final Long ENTITY_ID = 42L;

    /**
     * Hand-written test double — the JDK in use breaks Mockito's inline mock maker for non-final
     * concrete classes, mirroring the workaround in {@code DashboardControllerWhatsNewTest}.
     */
    static class StubRecommendedActionsService extends RecommendedActionsService {
        Long lastEntityId;
        boolean lastRefresh;
        RecommendedActionsResponse response;

        StubRecommendedActionsService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public RecommendedActionsResponse getRecommendedActions(Long entityId, boolean refresh) {
            lastEntityId = entityId;
            lastRefresh = refresh;
            return response;
        }
    }

    private StubRecommendedActionsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new StubRecommendedActionsService();
        EntityAccessService entityAccess = mock(EntityAccessService.class);
        // This endpoint is now gated behind Feature.CRISIS (GOLD) - see DashboardController#getRecommendedActions's
        // own doc. The admin bypass keeps this test's focus on the controller's own plumbing (path
        // variable/refresh-param passthrough, response shape) rather than entitlement/tier logic, which
        // EntitlementServiceTest already covers.
        when(entityAccess.currentUserIsAdmin()).thenReturn(true);
        DashboardController controller = new DashboardController(
                null, null, null, null, entityAccess, null, null, service, null, null, null,
                new EntitlementServiceImpl(mock(LicenseService.class), entityAccess, new PreviewMaskingServiceImpl()));

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter jacksonConverter = new MappingJackson2HttpMessageConverter(mapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(jacksonConverter)
                .build();
    }

    @Test
    void getRecommendedActions_defaultsRefreshToFalse() throws Exception {
        service.response = new RecommendedActionsResponse(
                ENTITY_ID, "Test Movie", 5,
                List.of(new RecommendedActionItem("test-candidate-1", RecommendedActionCategory.HIGH_IMPACT, "Title", "Reason", 90, "Factor", -10, 10,
                        "Release week", List.of(), List.of(), RecommendedActionStatus.ACTIVE)),
                Instant.parse("2026-08-09T10:00:00Z"), null);

        mvc.perform(get("/api/dashboard/{entityId}/recommended-actions", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(true))
                .andExpect(jsonPath("$.data.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.data.entityName").value("Test Movie"))
                .andExpect(jsonPath("$.data.daysToRelease").value(5))
                .andExpect(jsonPath("$.data.actions[0].title").value("Title"))
                .andExpect(jsonPath("$.data.actions[0].category").value("HIGH_IMPACT"));

        assertThat(service.lastEntityId).isEqualTo(ENTITY_ID);
        assertThat(service.lastRefresh).isFalse();
    }

    @Test
    void getRecommendedActions_passesRefreshParam() throws Exception {
        service.response = new RecommendedActionsResponse(ENTITY_ID, "Test Movie", null, List.of(), Instant.now(), null);

        mvc.perform(get("/api/dashboard/{entityId}/recommended-actions", ENTITY_ID)
                        .param("refresh", "true"))
                .andExpect(status().isOk());

        assertThat(service.lastRefresh).isTrue();
    }
}
