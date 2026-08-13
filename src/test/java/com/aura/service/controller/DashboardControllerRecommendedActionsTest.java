package com.aura.service.controller;

import com.aura.service.dto.RecommendedActionItem;
import com.aura.service.dto.RecommendedActionsResponse;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.service.EntityAccessService;
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
        boolean lastAllPhases;
        RecommendedActionsResponse response;

        StubRecommendedActionsService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public RecommendedActionsResponse getRecommendedActions(Long entityId, boolean refresh, boolean allPhases) {
            lastEntityId = entityId;
            lastRefresh = refresh;
            lastAllPhases = allPhases;
            return response;
        }
    }

    private StubRecommendedActionsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new StubRecommendedActionsService();
        DashboardController controller = new DashboardController(
                null, null, null, null, mock(EntityAccessService.class), null, null, service);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter jacksonConverter = new MappingJackson2HttpMessageConverter(mapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(jacksonConverter)
                .build();
    }

    @Test
    void getRecommendedActions_defaultsRefreshAndAllPhasesToFalse() throws Exception {
        service.response = new RecommendedActionsResponse(
                ENTITY_ID, "Test Movie", 5,
                List.of(new RecommendedActionItem(
                        RecommendedActionCategory.HIGH_IMPACT, "Title", "Reason", 90, "Factor", -10, 10,
                        "Release week", List.of())),
                Instant.parse("2026-08-09T10:00:00Z"));

        mvc.perform(get("/api/dashboard/{entityId}/recommended-actions", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.entityName").value("Test Movie"))
                .andExpect(jsonPath("$.daysToRelease").value(5))
                .andExpect(jsonPath("$.actions[0].title").value("Title"))
                .andExpect(jsonPath("$.actions[0].category").value("HIGH_IMPACT"));

        assertThat(service.lastEntityId).isEqualTo(ENTITY_ID);
        assertThat(service.lastRefresh).isFalse();
        assertThat(service.lastAllPhases).isFalse();
    }

    @Test
    void getRecommendedActions_passesRefreshAndAllPhasesParams() throws Exception {
        service.response = new RecommendedActionsResponse(ENTITY_ID, "Test Movie", null, List.of(), Instant.now());

        mvc.perform(get("/api/dashboard/{entityId}/recommended-actions", ENTITY_ID)
                        .param("refresh", "true")
                        .param("allPhases", "true"))
                .andExpect(status().isOk());

        assertThat(service.lastRefresh).isTrue();
        assertThat(service.lastAllPhases).isTrue();
    }
}
