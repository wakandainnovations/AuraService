package com.aura.service.controller;

import com.aura.service.entity.User;
import com.aura.service.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private static final String USERNAME = "ops_user";

    private UserRepository userRepository;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        UserController controller = new UserController(userRepository);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(USERNAME).password("x").authorities("USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private User existingUser() {
        User u = new User();
        u.setId(1L);
        u.setUsername(USERNAME);
        u.setPassword("x");
        u.setRole("USER");
        return u;
    }

    @Test
    void putWebhook_setsUrlAndReturnsCurrentState() throws Exception {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(existingUser()));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = mapper.writeValueAsString(Map.of("webhookUrl", "https://hooks.example.com/aura"));

        mvc.perform(put("/api/users/me/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.alertWebhookUrl").value("https://hooks.example.com/aura"));

        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAlertWebhookUrl()).isEqualTo("https://hooks.example.com/aura");
    }

    @Test
    void putWebhook_blankBodyClearsUrl() throws Exception {
        User existing = existingUser();
        existing.setAlertWebhookUrl("https://old.example.com/hook");
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = mapper.writeValueAsString(Map.of("webhookUrl", "   "));

        mvc.perform(put("/api/users/me/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertWebhookUrl").doesNotExist());

        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAlertWebhookUrl()).isNull();
    }

    @Test
    void putWebhook_returns404WhenUserMissing() throws Exception {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());

        String body = mapper.writeValueAsString(Map.of("webhookUrl", "https://hooks.example.com/aura"));

        mvc.perform(put("/api/users/me/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }
}
