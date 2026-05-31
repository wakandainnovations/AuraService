package com.aura.service.controller;

import com.aura.service.entity.CrisisPlan;
import com.aura.service.entity.User;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.PlaybookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlaybookControllerTest {

    private CrisisPlanRepository playbookRepository;
    private UserRepository userRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        playbookRepository = mock(CrisisPlanRepository.class);
        userRepository = mock(UserRepository.class);

        PlaybookService service = new PlaybookService(playbookRepository);
        PlaybookController controller = new PlaybookController(service, userRepository);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(username).password("x").roles("USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private CrisisPlan plan(Long id, List<String> tags) {
        return CrisisPlan.builder()
                .id(id)
                .entityId(5L)
                .mentionId(100L)
                .title("Negative review surge")
                .planText("1. Acknowledge.")
                .tags(new ArrayList<>(tags))
                .isFavorite(true)
                .createdBy(3L)
                .createdAt(Instant.parse("2026-05-10T00:00:00Z"))
                .build();
    }

    @Test
    void list_filtersByEntityAndReturnsResults() throws Exception {
        when(playbookRepository.findByEntityId(5L)).thenReturn(List.of(plan(1L, List.of("review"))));

        mvc.perform(MockMvcRequestBuilders.get("/api/playbooks")
                        .param("entityId", "5")
                        .param("tag", "review")
                        .param("favorite", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].tags[0]").value("review"))
                .andExpect(jsonPath("$[0].isFavorite").value(true));
    }

    @Test
    void update_editsTitleAndFavorite() throws Exception {
        when(playbookRepository.findById(1L)).thenReturn(Optional.of(plan(1L, List.of("review"))));
        when(playbookRepository.save(any(CrisisPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(MockMvcRequestBuilders.put("/api/playbooks/{id}", 1L)
                        .contentType("application/json")
                        .content("{\"title\":\"Renamed\",\"isFavorite\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.isFavorite").value(false));
    }

    @Test
    void clone_createsNewPlanOwnedByCaller() throws Exception {
        when(playbookRepository.findById(1L)).thenReturn(Optional.of(plan(1L, List.of("review"))));
        when(playbookRepository.save(any(CrisisPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        User caller = new User();
        caller.setId(7L);
        caller.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(caller));
        authenticateAs("alice");

        mvc.perform(MockMvcRequestBuilders.post("/api/playbooks/{id}/clone", 1L)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Copy of Negative review surge"))
                .andExpect(jsonPath("$.createdBy").value(7))
                .andExpect(jsonPath("$.isFavorite").value(false));
    }

    @Test
    void update_returns400WhenPlanMissing() throws Exception {
        when(playbookRepository.findById(anyLong())).thenReturn(Optional.empty());

        mvc.perform(MockMvcRequestBuilders.put("/api/playbooks/{id}", 99L)
                        .contentType("application/json")
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
