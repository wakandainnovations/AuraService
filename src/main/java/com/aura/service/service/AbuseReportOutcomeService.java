package com.aura.service.service;

import com.aura.service.entity.AbuseReport;
import com.aura.service.repository.AbuseReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

/**
 * Stub moderation backend: SUBMITTED abuse reports sit in review for {@link #REVIEW_DELAY}, then
 * transition to a random terminal outcome ({@link AbuseReport.Status#UPHELD} or
 * {@link AbuseReport.Status#REJECTED}). This stands in for real per-platform moderation callbacks so
 * the report lifecycle — and the "reward of the self" What's-New cards an UPHELD outcome produces —
 * is exercisable end-to-end without a live moderation integration.
 */
@Slf4j
@Service
public class AbuseReportOutcomeService {

    static final Duration REVIEW_DELAY = Duration.ofHours(24);

    private final AbuseReportRepository abuseReportRepository;
    private final Clock clock;
    private final Random random;

    @Autowired
    public AbuseReportOutcomeService(AbuseReportRepository abuseReportRepository, Clock clock) {
        this(abuseReportRepository, clock, new Random());
    }

    AbuseReportOutcomeService(AbuseReportRepository abuseReportRepository, Clock clock, Random random) {
        this.abuseReportRepository = abuseReportRepository;
        this.clock = clock;
        this.random = random;
    }

    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void resolvePendingReports() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(REVIEW_DELAY);
        List<AbuseReport> pending =
                abuseReportRepository.findByStatusAndSubmittedAtBefore(AbuseReport.Status.SUBMITTED, cutoff);
        for (AbuseReport report : pending) {
            try {
                AbuseReport.Status outcome = random.nextBoolean()
                        ? AbuseReport.Status.UPHELD
                        : AbuseReport.Status.REJECTED;
                report.setStatus(outcome);
                report.setResolvedAt(now);
                // Managed within this transaction; dirty checking flushes the new status + resolvedAt.
                log.info("STUB moderation outcome: report {} (mention {}) resolved {} after review",
                        report.getId(), report.getMentionId(), outcome);
            } catch (Exception e) {
                log.error("Failed to resolve abuse report {}", report.getId(), e);
            }
        }
    }
}
