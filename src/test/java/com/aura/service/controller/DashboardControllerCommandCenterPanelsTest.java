package com.aura.service.controller;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.Sentiment;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.service.DashboardService;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.ImpressionsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the five Command Center panel endpoints (Movie Health / Buzz / Sentiment / Reach /
 * Awareness) at the routing layer: correct path wiring, response shape, and that ownership is
 * enforced (404) before any dashboard data is read — same wiring {@link DashboardController}
 * uses for every other {@code /{entityId}/...} endpoint. Computation edge cases (score-to-percentage
 * mapping, buzz delta math, awareness tiering, ...) are covered in
 * {@code DashboardServiceCommandCenterPanelsTest}, not duplicated here.
 */
class DashboardControllerCommandCenterPanelsTest {

    private static final Long ENTITY_ID = 42L;

    private MentionRepository mentionRepository;
    private ManagedEntityRepository entityRepository;
    private EntityAccessService entityAccessService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);
        entityAccessService = mock(EntityAccessService.class);

        DashboardService dashboardService = new DashboardService(
                mentionRepository,
                entityRepository,
                mock(ReplyDraftRepository.class),
                mock(CrisisPlanRepository.class),
                mock(CheckpointRepository.class),
                new ImpressionsResolver(mentionRepository)
        );

        DashboardController controller = new DashboardController(
                dashboardService, null, null, null, entityAccessService, null, null, null);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ManagedEntity entity(Long id, String name) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName(name);
        e.setType("MOVIE");
        return e;
    }

    // ------------------------------------------------------------------
    // Movie Health
    // ------------------------------------------------------------------

    @Test
    void getMovieHealth_returnsComputedScoreAndLabel() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.POSITIVE)).thenReturn(30L);
        when(mentionRepository.countByManagedEntityIdAndSentiment(ENTITY_ID, Sentiment.NEGATIVE)).thenReturn(10L);

        mvc.perform(get("/api/dashboard/{entityId}/movie-health", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.entityName").value("Test Movie"))
                .andExpect(jsonPath("$.netSentimentScore").value(3.0))
                .andExpect(jsonPath("$.healthPercentage").value(100.0))
                .andExpect(jsonPath("$.healthLabel").value("Excellent"));

        verify(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);
    }

    @Test
    void getMovieHealth_returns404WhenEntityNotOwnedByCaller() throws Exception {
        doThrow(new ResourceNotFoundException("Entity not found with id: " + ENTITY_ID))
                .when(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);

        mvc.perform(get("/api/dashboard/{entityId}/movie-health", ENTITY_ID))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Buzz
    // ------------------------------------------------------------------

    @Test
    void getBuzz_returnsTodayVsYesterdayDelta() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(150L)
                .thenReturn(100L);

        mvc.perform(get("/api/dashboard/{entityId}/buzz", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.mentionsToday").value(150))
                .andExpect(jsonPath("$.mentionsYesterday").value(100))
                .andExpect(jsonPath("$.mentionsChange").value(50))
                .andExpect(jsonPath("$.mentionsChangePct").value(50.0));

        verify(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);
    }

    @Test
    void getBuzz_returns404WhenEntityNotOwnedByCaller() throws Exception {
        doThrow(new ResourceNotFoundException("Entity not found with id: " + ENTITY_ID))
                .when(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);

        mvc.perform(get("/api/dashboard/{entityId}/buzz", ENTITY_ID))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Sentiment
    // ------------------------------------------------------------------

    @Test
    void getSentiment_returnsAverageScoreAndPositiveRatio() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countByManagedEntityId(ENTITY_ID)).thenReturn(100L);
        when(mentionRepository.getSentimentStats(ENTITY_ID))
                .thenReturn(Optional.of(new com.aura.service.dto.SentimentStats(1.8, 0.65)));

        mvc.perform(get("/api/dashboard/{entityId}/sentiment", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.totalMentions").value(100))
                .andExpect(jsonPath("$.averageSentimentScore").value(1.8))
                .andExpect(jsonPath("$.positiveRatio").value(0.65));

        verify(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);
    }

    @Test
    void getSentiment_returns404WhenEntityNotOwnedByCaller() throws Exception {
        doThrow(new ResourceNotFoundException("Entity not found with id: " + ENTITY_ID))
                .when(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);

        mvc.perform(get("/api/dashboard/{entityId}/sentiment", ENTITY_ID))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Reach
    // ------------------------------------------------------------------

    @Test
    void getReach_returnsUniqueUserCount() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity(ENTITY_ID, "Test Movie")));
        when(mentionRepository.countDistinctAuthorsByEntityId(ENTITY_ID)).thenReturn(4321L);

        mvc.perform(get("/api/dashboard/{entityId}/reach", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.uniqueUsers").value(4321));

        verify(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);
    }

    @Test
    void getReach_returns404WhenEntityNotOwnedByCaller() throws Exception {
        doThrow(new ResourceNotFoundException("Entity not found with id: " + ENTITY_ID))
                .when(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);

        mvc.perform(get("/api/dashboard/{entityId}/reach", ENTITY_ID))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Awareness
    // ------------------------------------------------------------------

    @Test
    void getAwareness_returnsTotalViewsAndLevel() throws Exception {
        ManagedEntity target = entity(ENTITY_ID, "Test Movie");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(target));
        // No owner set -> compares against every MOVIE entity.
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of(target, entity(2L, "Other Movie")));
        when(mentionRepository.findTotalViewsForEntities(anyList())).thenReturn(List.of(
                new Object[]{ENTITY_ID, 900_000L},
                new Object[]{2L, 10_000L}
        ));

        mvc.perform(get("/api/dashboard/{entityId}/awareness", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.totalViews").value(900_000))
                .andExpect(jsonPath("$.awarenessLevel").value("High"))
                .andExpect(jsonPath("$.comparedMovieCount").value(2));

        verify(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);
    }

    @Test
    void getAwareness_returns404WhenEntityNotOwnedByCaller() throws Exception {
        doThrow(new ResourceNotFoundException("Entity not found with id: " + ENTITY_ID))
                .when(entityAccessService).assertOwnedByCurrentUser(ENTITY_ID);

        mvc.perform(get("/api/dashboard/{entityId}/awareness", ENTITY_ID))
                .andExpect(status().isNotFound());
    }
}
