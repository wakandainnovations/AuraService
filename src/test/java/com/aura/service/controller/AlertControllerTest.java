package com.aura.service.controller;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.SentimentAlertRepository;
import com.aura.service.service.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertControllerTest {

    private static final Instant NOW = Instant.parse("2026-05-21T12:00:00Z");
    private static final Long ENTITY_ID = 7L;
    private static final String ENTITY_NAME = "Galaxy Quest";
    private static final String USERNAME = "ops_user";

    private SentimentAlertRepository alertRepository;
    private ManagedEntityRepository entityRepository;
    private Clock clock;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        alertRepository = mock(SentimentAlertRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        AlertService service = new AlertService(alertRepository, entityRepository, clock);
        AlertController controller = new AlertController(service);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        UserDetails user = User.withUsername(USERNAME).password("x").authorities("USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private SentimentAlert spike(Long id, SentimentAlert.Status status, Instant triggeredAt) {
        return SentimentAlert.builder()
                .id(id)
                .managedEntityId(ENTITY_ID)
                .triggeredAt(triggeredAt)
                .kind(SentimentAlert.Kind.SPIKE)
                .currentValue(0.55)
                .baselineValue(0.20)
                .status(status)
                .build();
    }

    private SentimentAlert influencerNegative(Long id, SentimentAlert.Status status, String author) {
        return SentimentAlert.builder()
                .id(id)
                .managedEntityId(ENTITY_ID)
                .triggeredAt(NOW)
                .kind(SentimentAlert.Kind.INFLUENCER_NEGATIVE)
                .currentValue(0.0)
                .baselineValue(0.0)
                .status(status)
                .sourceMentionId(99L)
                .matchedAuthor(author)
                .permalink("https://x.com/" + author + "/99")
                .build();
    }

    private void stubEntityLookup() {
        ManagedEntity e = new ManagedEntity();
        e.setId(ENTITY_ID);
        e.setName(ENTITY_NAME);
        when(entityRepository.findAllById(eq(List.of(ENTITY_ID)))).thenReturn(List.of(e));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(e));
    }

    @Test
    void list_filtersByEntityIdAndStatusSortedByTriggeredAtDesc() throws Exception {
        stubEntityLookup();
        SentimentAlert newer = spike(1L, SentimentAlert.Status.OPEN, NOW);
        SentimentAlert older = spike(2L, SentimentAlert.Status.OPEN, NOW.minusSeconds(300));
        Page<SentimentAlert> page = new PageImpl<>(List.of(newer, older),
                PageRequest.of(0, 20, Sort.by("triggeredAt").descending()), 2);
        when(alertRepository.findFiltered(eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any(Pageable.class)))
                .thenReturn(page);

        mvc.perform(get("/api/alerts")
                        .param("entityId", String.valueOf(ENTITY_ID))
                        .param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].entityName").value(ENTITY_NAME))
                .andExpect(jsonPath("$.content[0].reason").value(
                        "Negative-sentiment ratio rose to 55% (baseline 20%) for " + ENTITY_NAME))
                .andExpect(jsonPath("$.content[1].id").value(2));

        org.mockito.ArgumentCaptor<Pageable> pageableCaptor =
                org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(alertRepository).findFiltered(eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), pageableCaptor.capture());
        Sort sort = pageableCaptor.getValue().getSort();
        assertThat(sort.getOrderFor("triggeredAt")).isNotNull();
        assertThat(sort.getOrderFor("triggeredAt").isDescending()).isTrue();
    }

    @Test
    void list_passesNullFiltersWhenAbsent() throws Exception {
        when(alertRepository.findFiltered(eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/alerts"))
                .andExpect(status().isOk());

        verify(alertRepository).findFiltered(eq(null), eq(null), any(Pageable.class));
    }

    @Test
    void list_influencerNegativeReasonIncludesAuthorAndEntity() throws Exception {
        stubEntityLookup();
        SentimentAlert alert = influencerNegative(10L, SentimentAlert.Status.OPEN, "alice");
        when(alertRepository.findFiltered(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(alert), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].kind").value("INFLUENCER_NEGATIVE"))
                .andExpect(jsonPath("$.content[0].matchedAuthor").value("alice"))
                .andExpect(jsonPath("$.content[0].permalink").value("https://x.com/alice/99"))
                .andExpect(jsonPath("$.content[0].reason").value(
                        "Top-50 spreader alice posted a negative mention about " + ENTITY_NAME));
    }

    @Test
    void ack_setsStatusAckedAndCurrentUser() throws Exception {
        stubEntityLookup();
        SentimentAlert existing = spike(33L, SentimentAlert.Status.OPEN, NOW.minusSeconds(60));
        when(alertRepository.findById(33L)).thenReturn(Optional.of(existing));
        when(alertRepository.save(any(SentimentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/alerts/{id}/ack", 33L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKED"))
                .andExpect(jsonPath("$.ackedBy").value(USERNAME));

        org.mockito.ArgumentCaptor<SentimentAlert> captor =
                org.mockito.ArgumentCaptor.forClass(SentimentAlert.class);
        verify(alertRepository).save(captor.capture());
        SentimentAlert saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SentimentAlert.Status.ACKED);
        assertThat(saved.getAckedBy()).isEqualTo(USERNAME);
        assertThat(saved.getAckedAt()).isEqualTo(NOW);
    }

    @Test
    void ack_returns404WhenAlertMissing() throws Exception {
        when(alertRepository.findById(404L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/alerts/{id}/ack", 404L))
                .andExpect(status().isNotFound());
    }

    @Test
    void dismiss_setsStatusDismissedReasonAndCurrentUser() throws Exception {
        stubEntityLookup();
        SentimentAlert existing = spike(44L, SentimentAlert.Status.OPEN, NOW.minusSeconds(60));
        when(alertRepository.findById(44L)).thenReturn(Optional.of(existing));
        when(alertRepository.save(any(SentimentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = mapper.writeValueAsString(java.util.Map.of("reason", "false positive — known reviewer"));

        mvc.perform(post("/api/alerts/{id}/dismiss", 44L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.dismissedBy").value(USERNAME))
                .andExpect(jsonPath("$.dismissReason").value("false positive — known reviewer"));

        org.mockito.ArgumentCaptor<SentimentAlert> captor =
                org.mockito.ArgumentCaptor.forClass(SentimentAlert.class);
        verify(alertRepository).save(captor.capture());
        SentimentAlert saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SentimentAlert.Status.DISMISSED);
        assertThat(saved.getDismissedBy()).isEqualTo(USERNAME);
        assertThat(saved.getDismissReason()).isEqualTo("false positive — known reviewer");
        assertThat(saved.getDismissedAt()).isEqualTo(NOW);
    }

    @Test
    void dismiss_returns400WhenReasonBlank() throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("reason", "   "));

        mvc.perform(post("/api/alerts/{id}/dismiss", 55L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dismiss_returns404WhenAlertMissing() throws Exception {
        when(alertRepository.findById(404L)).thenReturn(Optional.empty());

        String body = mapper.writeValueAsString(java.util.Map.of("reason", "no longer relevant"));

        mvc.perform(post("/api/alerts/{id}/dismiss", 404L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }
}
