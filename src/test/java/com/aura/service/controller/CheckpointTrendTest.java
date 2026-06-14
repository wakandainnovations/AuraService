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
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.ImpressionsResolver;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckpointTrendTest {

    private static final Long ENTITY_ID = 7L;
    private static final String ENTITY_NAME = "Galaxy Quest";
    private static final LocalDate RELEASE_DATE = LocalDate.of(2026, 1, 1);

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
                crisisPlanRepository, checkpointRepository,
                new ImpressionsResolver(mentionRepository));

        DashboardController controller = new DashboardController(
                dashboardService, null, null, null, mock(EntityAccessService.class));

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
        e.setReleaseDate(RELEASE_DATE);
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

    private Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant endOfDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
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

    private void stubCumulativeMentions(Long entityId, LocalDate upToDate, long count) {
        when(mentionRepository.countByManagedEntityIdAndPostDateLessThanEqual(
                eq(entityId), eq(endOfDay(upToDate))))
                .thenReturn(count);
    }

    @Test
    void threeCheckpoints_verifiesPeriodMentionsAndCumulativeCounts() throws Exception {
        LocalDate date1 = LocalDate.of(2026, 2, 1);
        LocalDate date2 = LocalDate.of(2026, 3, 1);
        LocalDate date3 = LocalDate.of(2026, 4, 1);

        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(
                        checkpoint(1L, date1, "First event"),
                        checkpoint(2L, date2, "Second event"),
                        checkpoint(3L, date3, "Third event")));

        // Period 1: [releaseDate, date1] = [2026-01-01, 2026-02-01]
        stubSentimentCounts(ENTITY_ID, startOfDay(RELEASE_DATE), endOfDay(date1), 10, 5, 5);
        stubCumulativeMentions(ENTITY_ID, date1, 20);

        // Period 2: [date1+1, date2] = [2026-02-02, 2026-03-01]
        stubSentimentCounts(ENTITY_ID, startOfDay(date1.plusDays(1)), endOfDay(date2), 18, 6, 6);
        stubCumulativeMentions(ENTITY_ID, date2, 50);

        // Period 3: [date2+1, date3] = [2026-03-02, 2026-04-01]
        stubSentimentCounts(ENTITY_ID, startOfDay(date2.plusDays(1)), endOfDay(date3), 30, 10, 10);
        stubCumulativeMentions(ENTITY_ID, date3, 100);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-trend", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.entityName").value(ENTITY_NAME))
                .andExpect(jsonPath("$.trendPoints.length()").value(3))
                .andExpect(jsonPath("$.trendPoints[0].periodMentions").value(20))
                .andExpect(jsonPath("$.trendPoints[0].cumulativeMentions").value(20))
                .andExpect(jsonPath("$.trendPoints[1].periodMentions").value(30))
                .andExpect(jsonPath("$.trendPoints[1].cumulativeMentions").value(50))
                .andExpect(jsonPath("$.trendPoints[2].periodMentions").value(50))
                .andExpect(jsonPath("$.trendPoints[2].cumulativeMentions").value(100));
    }

    @Test
    void firstPoint_hasNullChangeFromPrevious() throws Exception {
        LocalDate date1 = LocalDate.of(2026, 2, 1);
        LocalDate date2 = LocalDate.of(2026, 3, 1);

        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(
                        checkpoint(1L, date1, "First event"),
                        checkpoint(2L, date2, "Second event")));

        stubSentimentCounts(ENTITY_ID, startOfDay(RELEASE_DATE), endOfDay(date1), 10, 5, 5);
        stubCumulativeMentions(ENTITY_ID, date1, 20);

        stubSentimentCounts(ENTITY_ID, startOfDay(date1.plusDays(1)), endOfDay(date2), 18, 6, 6);
        stubCumulativeMentions(ENTITY_ID, date2, 50);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-trend", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trendPoints[0].positiveRatioChangeFromPrevious").isEmpty())
                .andExpect(jsonPath("$.trendPoints[0].netSentimentChangeFromPrevious").isEmpty())
                .andExpect(jsonPath("$.trendPoints[1].positiveRatioChangeFromPrevious").isNotEmpty())
                .andExpect(jsonPath("$.trendPoints[1].netSentimentChangeFromPrevious").isNotEmpty());
    }

    @Test
    void changeCalculations_betweenConsecutiveCheckpoints() throws Exception {
        LocalDate date1 = LocalDate.of(2026, 2, 1);
        LocalDate date2 = LocalDate.of(2026, 3, 1);
        LocalDate date3 = LocalDate.of(2026, 4, 1);

        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(
                        checkpoint(1L, date1, "First event"),
                        checkpoint(2L, date2, "Second event"),
                        checkpoint(3L, date3, "Third event")));

        // Period 1: pos=10, neg=5, neut=5 → ratio=0.5, net=2.0
        stubSentimentCounts(ENTITY_ID, startOfDay(RELEASE_DATE), endOfDay(date1), 10, 5, 5);
        stubCumulativeMentions(ENTITY_ID, date1, 20);

        // Period 2: pos=15, neg=5, neut=0 → ratio=0.75, net=3.0
        stubSentimentCounts(ENTITY_ID, startOfDay(date1.plusDays(1)), endOfDay(date2), 15, 5, 0);
        stubCumulativeMentions(ENTITY_ID, date2, 40);

        // Period 3: pos=5, neg=5, neut=10 → ratio=0.25, net=1.0
        stubSentimentCounts(ENTITY_ID, startOfDay(date2.plusDays(1)), endOfDay(date3), 5, 5, 10);
        stubCumulativeMentions(ENTITY_ID, date3, 60);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-trend", ENTITY_ID))
                .andExpect(status().isOk())
                // cp1: ratio=0.5, net=2.0, changes=null
                .andExpect(jsonPath("$.trendPoints[0].positiveRatio").value(0.5))
                .andExpect(jsonPath("$.trendPoints[0].netSentiment").value(2.0))
                .andExpect(jsonPath("$.trendPoints[0].positiveRatioChangeFromPrevious").isEmpty())
                .andExpect(jsonPath("$.trendPoints[0].netSentimentChangeFromPrevious").isEmpty())
                // cp2: ratio=0.75, net=3.0, ratioChange=0.25, netChange=1.0
                .andExpect(jsonPath("$.trendPoints[1].positiveRatio").value(0.75))
                .andExpect(jsonPath("$.trendPoints[1].netSentiment").value(3.0))
                .andExpect(jsonPath("$.trendPoints[1].positiveRatioChangeFromPrevious").value(0.25))
                .andExpect(jsonPath("$.trendPoints[1].netSentimentChangeFromPrevious").value(1.0))
                // cp3: ratio=0.25, net=1.0, ratioChange=-0.5, netChange=-2.0
                .andExpect(jsonPath("$.trendPoints[2].positiveRatio").value(0.25))
                .andExpect(jsonPath("$.trendPoints[2].netSentiment").value(1.0))
                .andExpect(jsonPath("$.trendPoints[2].positiveRatioChangeFromPrevious").value(-0.5))
                .andExpect(jsonPath("$.trendPoints[2].netSentimentChangeFromPrevious").value(-2.0));
    }

    @Test
    void noCheckpoints_returnsEmptyTrendList() throws Exception {
        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(Collections.emptyList());

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-trend", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.entityName").value(ENTITY_NAME))
                .andExpect(jsonPath("$.trendPoints.length()").value(0));
    }

    @Test
    void singleCheckpoint_usesReleaseDateAsStart() throws Exception {
        LocalDate cpDate = LocalDate.of(2026, 3, 1);

        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(List.of(checkpoint(1L, cpDate, "Solo event")));

        // Period starts from entity releaseDate (2026-01-01), not from checkpoint date
        stubSentimentCounts(ENTITY_ID, startOfDay(RELEASE_DATE), endOfDay(cpDate), 25, 5, 10);
        stubCumulativeMentions(ENTITY_ID, cpDate, 40);

        mvc.perform(get("/api/dashboard/{entityId}/checkpoint-trend", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trendPoints.length()").value(1))
                .andExpect(jsonPath("$.trendPoints[0].checkpointDate").value("2026-03-01"))
                .andExpect(jsonPath("$.trendPoints[0].description").value("Solo event"))
                .andExpect(jsonPath("$.trendPoints[0].periodMentions").value(40))
                .andExpect(jsonPath("$.trendPoints[0].cumulativeMentions").value(40))
                .andExpect(jsonPath("$.trendPoints[0].positiveRatio").value(0.625))
                .andExpect(jsonPath("$.trendPoints[0].netSentiment").value(5.0))
                .andExpect(jsonPath("$.trendPoints[0].positiveRatioChangeFromPrevious").isEmpty())
                .andExpect(jsonPath("$.trendPoints[0].netSentimentChangeFromPrevious").isEmpty());
    }
}
