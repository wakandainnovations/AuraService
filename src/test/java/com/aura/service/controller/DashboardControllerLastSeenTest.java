package com.aura.service.controller;

import com.aura.service.entity.User;
import com.aura.service.repository.UserEntityViewRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.UserEntityViewService;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardControllerLastSeenTest {

    private static final Instant NOW = Instant.parse("2026-05-23T12:00:00Z");
    private static final Instant PAST = Instant.parse("2026-05-21T09:30:00Z");
    private static final Long ENTITY_ID = 7L;
    private static final Long USER_ID = 42L;
    private static final String USERNAME = "ops_user";

    private UserEntityViewRepository viewRepository;
    private UserRepository userRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        viewRepository = mock(UserEntityViewRepository.class);
        userRepository = mock(UserRepository.class);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UserEntityViewService viewService =
                new UserEntityViewService(viewRepository, userRepository, clock);
        DashboardController controller = new DashboardController(
                null, viewService, null, null, mock(EntityAccessService.class), null, null, null);

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

        User user = new User();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setPassword("x");
        user.setRole("USER");
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
    }

    @Test
    void getLastSeen_returnsStoredInstant() throws Exception {
        authenticate();
        when(viewRepository.findLastSeen(USER_ID, ENTITY_ID)).thenReturn(Optional.of(PAST));

        mvc.perform(get("/api/dashboard/{entityId}/last-seen", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSeenAt").value(PAST.toString()));
    }

    @Test
    void getLastSeen_returnsNullWhenNeverViewed() throws Exception {
        authenticate();
        when(viewRepository.findLastSeen(USER_ID, ENTITY_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/api/dashboard/{entityId}/last-seen", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist());
    }

    @Test
    void getLastSeen_returnsNullWhenUserMissing() throws Exception {
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(USERNAME).password("x").authorities("USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());

        mvc.perform(get("/api/dashboard/{entityId}/last-seen", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist());
    }
}
