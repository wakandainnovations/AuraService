package com.aura.service.controller;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.Sentiment;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.service.DashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckpointImpactTest {

    private static final Long ENTITY_ID = 7L;
    private static final String ENTITY_NAME = "Galaxy Quest";

    private MentionRepository mentionRepository;
    private ManagedEntityRepository entityRepository;
    private CheckpointRepository checkpointRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);
        checkpointRepository = mock(CheckpointRepository.class);
        ReplyDraftRepository replyDraftRepository = mock(ReplyDraftRepository.class);
        CrisisPlanRepository crisisPlanRepository = mock(CrisisPlanRepository.class);

        DashboardService dashboardService = new DashboardService(
                mentionRepository, entityRepository, replyDraftRepository,
                crisisPlanRepository, checkpointRepository);

        DashboardController controller = new DashboardController(
                dashboardService, null, null, null);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter jacksonConverter =
                new MappingJackson2HttpMessageConverter(mapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jacksonConverter)
                .build();

        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity()));
    }

    private ManagedEntity entity() {
        ManagedEntity e = new ManagedEntity();
        e.setId(ENTITY_ID);
        e.setName(ENTITY_NAME);
        return e;
    }

    private Checkpoint checkpoint(Long id, LocalDate date, String description) {
        return Checkpoint.builder()
                .id(id)
                .managedEntity(entity())
                .checkpointDate(date)
                .description(description)
                .build();
    }

    private void stubSentimentCounts(Long entityId, Instant start, Instant end,
                                     long positive, long negative, long neutral) {
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                eq(entityId), eq(Sentiment.POSITIVE), eq(start), eq(end)))
                .thenReturn(positive);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                eq(entityId), eq(Sentiment.NEGATIVE), eq(start), eq(end)))
                .thenReturn(negative);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                eq(entityId), eq(Sentiment.NEUTRAL), eq(start), eq(end)))
                .thenReturn(neutral);
    }

    @Test
    void multipleCheckpoints_returnsCorrectBeforeAfterStats() throws Exception {
        LocalDate date1 = LocalDate.of(2026, 3, 10);
        LocalDate date2 = LocalDate.of(2026, 4, 20);
        int windowDays = 7;

        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(
                        checkpoint(1L, date1, "Audio launch"),
                        checkpoint(2L, date2, "Teaser release")));

        Instant before1Start = date1.minusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant before1End = date1.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        Instant after1Start = date1.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant after1End = date1.plusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        stubSentimentCounts(ENTITY_ID, before1Start, before1End, 10, 5, 5);
        stubSentimentCounts(ENTITY_ID, after1Start, after1End, 30, 5, 5);

        Instant before2Start = date2.minusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant before2End = date2.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        Instant after2Start = date2.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant after2End = date2.plusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        stubSentimentCounts(ENTITY_ID, before2Start, before2End, 20, 10, 10);
        stubSentimentCounts(ENTITY_ID, after2Start, after2End, 15, 20, 5);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-impact", ENTITY_ID)
                        .param("windowDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.entityName").value(ENTITY_NAME))
                .andExpect(jsonPath("$.windowDays").value(7))
                .andExpect(jsonPath("$.impacts.length()").value(2))
                .andExpect(jsonPath("$.impacts[0].checkpointId").value(1))
                .andExpect(jsonPath("$.impacts[0].checkpointDate").value("2026-03-10"))
                .andExpect(jsonPath("$.impacts[0].description").value("Audio launch"))
                .andExpect(jsonPath("$.impacts[0].beforeTotalMentions").value(20))
                .andExpect(jsonPath("$.impacts[0].afterTotalMentions").value(40))
                .andExpect(jsonPath("$.impacts[0].beforePositiveRatio").value(0.5))
                .andExpect(jsonPath("$.impacts[0].afterPositiveRatio").value(0.75))
                .andExpect(jsonPath("$.impacts[0].impactDirection").value("POSITIVE"))
                .andExpect(jsonPath("$.impacts[1].checkpointId").value(2))
                .andExpect(jsonPath("$.impacts[1].checkpointDate").value("2026-04-20"))
                .andExpect(jsonPath("$.impacts[1].description").value("Teaser release"))
                .andExpect(jsonPath("$.impacts[1].beforeTotalMentions").value(40))
                .andExpect(jsonPath("$.impacts[1].afterTotalMentions").value(40))
                .andExpect(jsonPath("$.impacts[1].impactDirection").value("NEGATIVE"));
    }

    @Test
    void impactDirection_positiveWhenChangeAboveThreshold() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 1);
        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(checkpoint(1L, date, "Movie release")));

        Instant beforeStart = date.minusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant beforeEnd = date.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        Instant afterStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant afterEnd = date.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        stubSentimentCounts(ENTITY_ID, beforeStart, beforeEnd, 10, 10, 10);
        stubSentimentCounts(ENTITY_ID, afterStart, afterEnd, 25, 5, 10);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-impact", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impacts[0].impactDirection").value("POSITIVE"));
    }

    @Test
    void impactDirection_negativeWhenChangeBelowThreshold() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 1);
        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(checkpoint(1L, date, "Movie release")));

        Instant beforeStart = date.minusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant beforeEnd = date.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        Instant afterStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant afterEnd = date.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        stubSentimentCounts(ENTITY_ID, beforeStart, beforeEnd, 20, 5, 5);
        stubSentimentCounts(ENTITY_ID, afterStart, afterEnd, 5, 20, 5);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-impact", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impacts[0].impactDirection").value("NEGATIVE"));
    }

    @Test
    void impactDirection_neutralWhenChangeWithinThreshold() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 1);
        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(checkpoint(1L, date, "Movie release")));

        Instant beforeStart = date.minusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant beforeEnd = date.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        Instant afterStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant afterEnd = date.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        stubSentimentCounts(ENTITY_ID, beforeStart, beforeEnd, 10, 10, 10);
        stubSentimentCounts(ENTITY_ID, afterStart, afterEnd, 10, 10, 10);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-impact", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impacts[0].netSentimentChange").value(0.0))
                .andExpect(jsonPath("$.impacts[0].impactDirection").value("NEUTRAL"));
    }

    @Test
    void noCheckpoints_returnsEmptyImpactsList() throws Exception {
        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(Collections.emptyList());

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-impact", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.entityName").value(ENTITY_NAME))
                .andExpect(jsonPath("$.windowDays").value(7))
                .andExpect(jsonPath("$.impacts.length()").value(0));
    }

    @Test
    void customWindowDays_usesProvidedWindow() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 1);
        int windowDays = 14;

        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(checkpoint(1L, date, "Audio launch")));

        Instant beforeStart = date.minusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant beforeEnd = date.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        Instant afterStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant afterEnd = date.plusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        stubSentimentCounts(ENTITY_ID, beforeStart, beforeEnd, 50, 10, 20);
        stubSentimentCounts(ENTITY_ID, afterStart, afterEnd, 60, 10, 30);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-impact", ENTITY_ID)
                        .param("windowDays", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(14))
                .andExpect(jsonPath("$.impacts[0].beforeTotalMentions").value(80))
                .andExpect(jsonPath("$.impacts[0].afterTotalMentions").value(100));
    }

    @Test
    void beforeWindowExcludesCheckpointDate_afterWindowIncludesIt() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 15);
        int windowDays = 7;

        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(checkpoint(1L, date, "Movie release")));

        Instant beforeStart = date.minusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant beforeEnd = date.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        Instant afterStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant afterEnd = date.plusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        stubSentimentCounts(ENTITY_ID, beforeStart, beforeEnd, 10, 5, 5);
        stubSentimentCounts(ENTITY_ID, afterStart, afterEnd, 20, 5, 5);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-impact", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impacts[0].beforeTotalMentions").value(20))
                .andExpect(jsonPath("$.impacts[0].afterTotalMentions").value(30));

        // before window: [2026-05-08T00:00:00Z, 2026-05-14T23:59:59.999999999Z] — excludes checkpoint date
        // after window:  [2026-05-15T00:00:00Z, 2026-05-21T23:59:59.999999999Z] — includes checkpoint date
        Instant checkpointMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        assert beforeEnd.isBefore(checkpointMidnight);
        assert !afterStart.isAfter(checkpointMidnight);
    }
}
