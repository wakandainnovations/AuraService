package com.aura.service.controller;

import com.aura.service.dto.WhatsChangedResponse;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.WhatsChangedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardControllerWhatsChangedTest {

    private static final Long ENTITY_ID = 7L;
    private static final String USERNAME = "ops_user";

    /**
     * Hand-written test double — JDK in use breaks Mockito's inline mock maker for non-final
     * concrete classes (see SentimentAlertServiceTest for the same workaround).
     */
    static class StubWhatsChanged extends WhatsChangedService {
        private final java.util.Map<String, WhatsChangedResponse> responsesByUsername =
                new java.util.HashMap<>();

        StubWhatsChanged() {
            super(null, null, null, null, null);
        }

        void put(String username, Long entityId, WhatsChangedResponse response) {
            responsesByUsername.put(key(username, entityId), response);
        }

        @Override
        public WhatsChangedResponse computeDelta(String username, Long entityId) {
            return responsesByUsername.getOrDefault(
                    key(username, entityId), new WhatsChangedResponse());
        }

        private static String key(String username, Long entityId) {
            return username + ":" + entityId;
        }
    }

    private StubWhatsChanged whatsChangedService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        whatsChangedService = new StubWhatsChanged();
        DashboardController controller = new DashboardController(
                null, null, whatsChangedService, null, mock(EntityAccessService.class), null);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter jacksonConverter =
                new MappingJackson2HttpMessageConverter(mapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(jacksonConverter)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private void authenticate() {
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(USERNAME).password("x").authorities("USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void getWhatsChanged_returnsSnakeCaseFields() throws Exception {
        authenticate();
        Map<String, Double> competitors = new LinkedHashMap<>();
        competitors.put("CompA", 1.5);
        WhatsChangedResponse body = new WhatsChangedResponse(0.75, 42L, 7L, 3L, competitors);
        whatsChangedService.put(USERNAME, ENTITY_ID, body);

        mvc.perform(get("/api/dashboard/{entityId}/whats-changed", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentiment_score_delta").value(0.75))
                .andExpect(jsonPath("$.new_mentions_count").value(42))
                .andExpect(jsonPath("$.new_negative_count").value(7))
                .andExpect(jsonPath("$.new_super_spreader_count").value(3))
                .andExpect(jsonPath("$.competitor_delta.CompA").value(1.5));
    }

    @Test
    void getWhatsChanged_returnsNullFieldsForFirstVisit() throws Exception {
        authenticate();
        // No stubbed response -> default empty WhatsChangedResponse from StubWhatsChanged.

        mvc.perform(get("/api/dashboard/{entityId}/whats-changed", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentiment_score_delta").doesNotExist())
                .andExpect(jsonPath("$.new_mentions_count").doesNotExist())
                .andExpect(jsonPath("$.competitor_delta").doesNotExist());
    }
}
