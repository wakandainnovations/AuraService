package com.aura.service.service;

import com.aura.service.controller.DashboardController;
import com.aura.service.dto.SentimentDeltaResponse;
import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SentimentDeltaTest {

    private static final Long ENTITY_ID = 1L;

    private MentionRepository mentionRepository;
    private CheckpointRepository checkpointRepository;
    private DashboardService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        checkpointRepository = mock(CheckpointRepository.class);
        service = new DashboardService(
                mentionRepository,
                mock(ManagedEntityRepository.class),
                mock(ReplyDraftRepository.class),
                mock(CrisisPlanRepository.class),
                checkpointRepository
        );

        DashboardController controller = new DashboardController(service, null, null, null);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void stubWindow(Long entityId, LocalDate date, int windowDays,
                            long positive, long negative, long neutral) {
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = date.plusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                eq(entityId), eq(Sentiment.POSITIVE), eq(start), eq(end))).thenReturn(positive);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                eq(entityId), eq(Sentiment.NEGATIVE), eq(start), eq(end))).thenReturn(negative);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                eq(entityId), eq(Sentiment.NEUTRAL), eq(start), eq(end))).thenReturn(neutral);
    }

    private Checkpoint checkpoint(Long entityId, LocalDate date, String description) {
        ManagedEntity e = new ManagedEntity();
        e.setId(entityId);
        e.setName("entity");
        return Checkpoint.builder()
                .id(1L)
                .managedEntity(e)
                .checkpointDate(date)
                .description(description)
                .build();
    }

    @Test
    void deltaCalculationWithKnownCounts() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 2, 1);
        int windowDays = 7;

        stubWindow(ENTITY_ID, from, windowDays, 30, 10, 60);
        stubWindow(ENTITY_ID, to, windowDays, 50, 5, 45);

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, from))
                .thenReturn(Optional.empty());
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, to))
                .thenReturn(Optional.empty());

        SentimentDeltaResponse r = service.getSentimentDelta(ENTITY_ID, from, to, windowDays);

        assertThat(r.getFromDate()).isEqualTo(from);
        assertThat(r.getToDate()).isEqualTo(to);
        assertThat(r.getFromTotalMentions()).isEqualTo(100);
        assertThat(r.getToTotalMentions()).isEqualTo(100);
        assertThat(r.getMentionsDelta()).isEqualTo(0);
        assertThat(r.getFromPositiveRatio()).isEqualTo(0.3);
        assertThat(r.getToPositiveRatio()).isEqualTo(0.5);
        assertThat(r.getPositiveRatioDelta()).isEqualTo(0.2);
        assertThat(r.getFromNetSentiment()).isEqualTo(3.0);
        assertThat(r.getToNetSentiment()).isEqualTo(10.0);
        assertThat(r.getNetSentimentDelta()).isEqualTo(7.0);
    }

    @Test
    void checkpointLabelsPopulatedWhenDatesMatchCheckpoints() {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 15);

        stubWindow(ENTITY_ID, from, 7, 10, 5, 5);
        stubWindow(ENTITY_ID, to, 7, 20, 10, 10);

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, from))
                .thenReturn(Optional.of(checkpoint(ENTITY_ID, from, "Product Launch")));
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, to))
                .thenReturn(Optional.of(checkpoint(ENTITY_ID, to, "PR Campaign")));

        SentimentDeltaResponse r = service.getSentimentDelta(ENTITY_ID, from, to, 7);

        assertThat(r.getFromLabel()).isEqualTo("Product Launch");
        assertThat(r.getToLabel()).isEqualTo("PR Campaign");
    }

    @Test
    void checkpointLabelsNullWhenDatesDoNotMatchCheckpoints() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 15);

        stubWindow(ENTITY_ID, from, 7, 10, 5, 5);
        stubWindow(ENTITY_ID, to, 7, 20, 10, 10);

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, from))
                .thenReturn(Optional.empty());
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, to))
                .thenReturn(Optional.empty());

        SentimentDeltaResponse r = service.getSentimentDelta(ENTITY_ID, from, to, 7);

        assertThat(r.getFromLabel()).isNull();
        assertThat(r.getToLabel()).isNull();
    }

    @Test
    void customWindowDaysChangesAggregationRange() {
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 5, 15);
        int customWindow = 14;

        stubWindow(ENTITY_ID, from, customWindow, 40, 20, 40);
        stubWindow(ENTITY_ID, to, customWindow, 60, 10, 30);

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, from))
                .thenReturn(Optional.empty());
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, to))
                .thenReturn(Optional.empty());

        SentimentDeltaResponse r = service.getSentimentDelta(ENTITY_ID, from, to, customWindow);

        assertThat(r.getFromTotalMentions()).isEqualTo(100);
        assertThat(r.getToTotalMentions()).isEqualTo(100);
        assertThat(r.getFromPositiveRatio()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.getToPositiveRatio()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.getPositiveRatioDelta()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void defaultWindowDaysIsSevenViaMockMvc() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 2, 1);

        stubWindow(ENTITY_ID, from, 7, 10, 5, 5);
        stubWindow(ENTITY_ID, to, 7, 10, 5, 5);

        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, from))
                .thenReturn(Optional.empty());
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, to))
                .thenReturn(Optional.empty());

        mvc.perform(get("/api/dashboard/{entityId}/sentiment-delta", ENTITY_ID)
                        .param("fromDate", "2026-01-01")
                        .param("toDate", "2026-02-01"))
                .andExpect(status().isOk());
    }

    @Test
    void fromDateNotBeforeToDateReturns400() throws Exception {
        mvc.perform(get("/api/dashboard/{entityId}/sentiment-delta", ENTITY_ID)
                        .param("fromDate", "2026-03-15")
                        .param("toDate", "2026-03-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameDateReturns400() throws Exception {
        mvc.perform(get("/api/dashboard/{entityId}/sentiment-delta", ENTITY_ID)
                        .param("fromDate", "2026-03-15")
                        .param("toDate", "2026-03-15"))
                .andExpect(status().isBadRequest());
    }
}
