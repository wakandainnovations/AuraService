package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.SentimentAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SentimentAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-21T12:00:00Z");
    private static final Long ENTITY_ID = 42L;

    private ManagedEntityRepository entityRepository;
    private MentionRepository mentionRepository;
    private SentimentAlertRepository alertRepository;
    private Clock clock;
    private SentimentAlertService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        mentionRepository = mock(MentionRepository.class);
        alertRepository = mock(SentimentAlertRepository.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new SentimentAlertService(entityRepository, mentionRepository, alertRepository, clock);

        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        when(entityRepository.findAll()).thenReturn(List.of(entity));
    }

    private void stubWindowCounts(long totalRolling, long negativeRolling,
                                  long totalBaseline, long negativeBaseline) {
        Instant rollingStart = NOW.minus(SentimentAlertService.ROLLING_WINDOW);
        Instant baselineStart = NOW.minus(SentimentAlertService.BASELINE_WINDOW);

        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                ENTITY_ID, rollingStart, NOW)).thenReturn(totalRolling);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                ENTITY_ID, Sentiment.NEGATIVE, rollingStart, NOW)).thenReturn(negativeRolling);

        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                ENTITY_ID, baselineStart, NOW)).thenReturn(totalBaseline);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                ENTITY_ID, Sentiment.NEGATIVE, baselineStart, NOW)).thenReturn(negativeBaseline);
    }

    @Test
    void createsAlertWhenRatioExceedsBaselineAndCountMeetsThreshold() {
        // current ratio = 30/60 = 0.5; baseline = 200/1000 = 0.2; 0.5 > 0.2 * 1.5 = 0.3
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(false);

        service.scanForSpikes();

        verify(alertRepository).save(any(SentimentAlert.class));
    }

    @Test
    void persistsExpectedFields() {
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(false);
        org.mockito.ArgumentCaptor<SentimentAlert> captor =
                org.mockito.ArgumentCaptor.forClass(SentimentAlert.class);

        service.scanForSpikes();

        verify(alertRepository).save(captor.capture());
        SentimentAlert saved = captor.getValue();
        assertThat(saved.getManagedEntityId()).isEqualTo(ENTITY_ID);
        assertThat(saved.getTriggeredAt()).isEqualTo(NOW);
        assertThat(saved.getKind()).isEqualTo(SentimentAlert.Kind.SPIKE);
        assertThat(saved.getStatus()).isEqualTo(SentimentAlert.Status.OPEN);
        assertThat(saved.getCurrentValue()).isEqualTo(0.5);
        assertThat(saved.getBaselineValue()).isEqualTo(0.2);
    }

    @Test
    void skipsWhenAbsoluteNegativeCountBelowMinimum() {
        // 9 < 10 even though ratio 9/10 dwarfs baseline
        stubWindowCounts(10, 9, 1000, 100);

        service.scanForSpikes();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void skipsWhenRatioDoesNotExceedBaselineMultiplier() {
        // current ratio = 0.25; baseline 0.2 * 1.5 = 0.3
        stubWindowCounts(40, 10, 1000, 200);

        service.scanForSpikes();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void skipsWhenBaselineIsZero() {
        // No baseline data -> baselineRatio=0, do not treat as infinite spike
        stubWindowCounts(60, 30, 0, 0);

        service.scanForSpikes();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void skipsWhenOpenAlertExistsWithinDedupWindow() {
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(true);

        service.scanForSpikes();

        verify(alertRepository, never()).save(any());
    }

    @Test
    void usesFixedClockForDedupWindowBoundary() {
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(false);
        org.mockito.ArgumentCaptor<Instant> dedupCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);

        service.scanForSpikes();

        verify(alertRepository).existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), dedupCaptor.capture());
        assertThat(dedupCaptor.getValue())
                .isEqualTo(NOW.minus(SentimentAlertService.DEDUP_WINDOW));
    }

    @Test
    void continuesProcessingOtherEntitiesAfterFailure() {
        ManagedEntity good = new ManagedEntity();
        good.setId(ENTITY_ID);
        ManagedEntity bad = new ManagedEntity();
        bad.setId(999L);
        when(entityRepository.findAll()).thenReturn(List.of(bad, good));

        Instant rollingStart = NOW.minus(SentimentAlertService.ROLLING_WINDOW);
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(999L, rollingStart, NOW))
                .thenThrow(new RuntimeException("boom"));
        stubWindowCounts(60, 30, 1000, 200);
        when(alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                eq(ENTITY_ID), eq(SentimentAlert.Status.OPEN), any())).thenReturn(false);

        service.scanForSpikes();

        verify(alertRepository).save(any(SentimentAlert.class));
    }
}
