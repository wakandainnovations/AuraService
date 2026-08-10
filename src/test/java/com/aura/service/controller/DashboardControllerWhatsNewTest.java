package com.aura.service.controller;

import com.aura.service.dto.WhatsNewCard;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.WhatsNewService;
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

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardControllerWhatsNewTest {

    private static final Long ENTITY_ID = 7L;
    private static final String USERNAME = "ops_user";

    /**
     * Hand-written test double — the JDK in use breaks Mockito's inline mock maker for non-final
     * concrete classes, mirroring the workaround in {@link DashboardControllerWhatsChangedTest}.
     */
    static class StubWhatsNew extends WhatsNewService {
        private final java.util.Map<String, List<WhatsNewCard>> responses = new java.util.HashMap<>();

        StubWhatsNew() {
            super(null, null, null, null, null, null);
        }

        void put(String username, Long entityId, List<WhatsNewCard> cards) {
            responses.put(key(username, entityId), cards);
        }

        @Override
        public List<WhatsNewCard> getCards(String username, Long entityId) {
            return responses.getOrDefault(key(username, entityId), List.of());
        }

        private static String key(String username, Long entityId) {
            return username + ":" + entityId;
        }
    }

    private StubWhatsNew whatsNewService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        whatsNewService = new StubWhatsNew();
        DashboardController controller = new DashboardController(
                null, null, null, whatsNewService, mock(EntityAccessService.class), null, null, null);

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
    void getWhatsNew_returnsSnakeCaseFields() throws Exception {
        authenticate();
        WhatsNewCard card = new WhatsNewCard(
                WhatsNewService.KIND_COMPETITOR_DROP,
                "CompA's sentiment dropped 0.40 since your last visit",
                -0.4,
                List.of(901L, 902L));
        whatsNewService.put(USERNAME, ENTITY_ID, List.of(card));

        mvc.perform(get("/api/dashboard/{entityId}/whats-new", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("COMPETITOR_DROP"))
                .andExpect(jsonPath("$[0].headline").value(card.getHeadline()))
                .andExpect(jsonPath("$[0].value").value(-0.4))
                .andExpect(jsonPath("$[0].evidence_mention_ids[0]").value(901))
                .andExpect(jsonPath("$[0].evidence_mention_ids[1]").value(902));
    }

    @Test
    void getWhatsNew_returnsEmptyArrayWhenNoCards() throws Exception {
        authenticate();

        mvc.perform(get("/api/dashboard/{entityId}/whats-new", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
