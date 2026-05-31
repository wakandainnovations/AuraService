package com.aura.service.service;

import com.aura.service.entity.AbuseReport;
import com.aura.service.repository.AbuseReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AbuseReportOutcomeServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-31T12:00:00Z");

    private AbuseReportRepository abuseReportRepository;

    @BeforeEach
    void setUp() {
        abuseReportRepository = mock(AbuseReportRepository.class);
    }

    private AbuseReportOutcomeService serviceWith(Random random) {
        return new AbuseReportOutcomeService(
                abuseReportRepository, Clock.fixed(NOW, ZoneOffset.UTC), random);
    }

    private static AbuseReport submitted(long id) {
        return AbuseReport.builder()
                .id(id)
                .mentionId(900L + id)
                .userId(1L)
                .category(AbuseReport.Category.HARASSMENT)
                .status(AbuseReport.Status.SUBMITTED)
                .submittedAt(NOW.minus(Duration.ofHours(30)))
                .build();
    }

    @Test
    void queriesSubmittedReportsOlderThanReviewWindow() {
        when(abuseReportRepository.findByStatusAndSubmittedAtBefore(
                eq(AbuseReport.Status.SUBMITTED), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        serviceWith(new Random(0L)).resolvePendingReports();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(abuseReportRepository).findByStatusAndSubmittedAtBefore(
                eq(AbuseReport.Status.SUBMITTED), cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        verifyNoMoreInteractions(abuseReportRepository);
    }

    @Test
    void transitionsPendingReportsToTerminalStatusAndStampsResolvedAt() {
        // Alternating coin so the test asserts both terminal outcomes deterministically.
        Random alternating = new Random() {
            private boolean next = true;
            @Override public boolean nextBoolean() {
                boolean value = next;
                next = !next;
                return value;
            }
        };
        AbuseReport first = submitted(1L);
        AbuseReport second = submitted(2L);
        when(abuseReportRepository.findByStatusAndSubmittedAtBefore(
                eq(AbuseReport.Status.SUBMITTED), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(first, second));

        serviceWith(alternating).resolvePendingReports();

        assertThat(first.getStatus()).isEqualTo(AbuseReport.Status.UPHELD);
        assertThat(first.getResolvedAt()).isEqualTo(NOW);
        assertThat(second.getStatus()).isEqualTo(AbuseReport.Status.REJECTED);
        assertThat(second.getResolvedAt()).isEqualTo(NOW);
        // Dirty checking inside the transaction flushes the change — no explicit save needed.
        verify(abuseReportRepository).findByStatusAndSubmittedAtBefore(
                eq(AbuseReport.Status.SUBMITTED), org.mockito.ArgumentMatchers.any());
        verifyNoMoreInteractions(abuseReportRepository);
    }

    @Test
    void everyOutcomeIsTerminal() {
        List<AbuseReport> reports = List.of(
                submitted(1L), submitted(2L), submitted(3L), submitted(4L), submitted(5L));
        when(abuseReportRepository.findByStatusAndSubmittedAtBefore(
                eq(AbuseReport.Status.SUBMITTED), org.mockito.ArgumentMatchers.any()))
                .thenReturn(reports);

        serviceWith(new Random(42L)).resolvePendingReports();

        assertThat(reports).allSatisfy(r -> {
            assertThat(r.getStatus()).isIn(AbuseReport.Status.UPHELD, AbuseReport.Status.REJECTED);
            assertThat(r.getResolvedAt()).isEqualTo(NOW);
        });
    }
}
