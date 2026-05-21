package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.SentimentAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SentimentAlertService {

    static final Duration ROLLING_WINDOW = Duration.ofMinutes(60);
    static final Duration BASELINE_WINDOW = Duration.ofDays(7);
    static final Duration DEDUP_WINDOW = Duration.ofMinutes(30);
    static final double SPIKE_MULTIPLIER = 1.5;
    static final long MIN_ABSOLUTE_COUNT = 10L;

    private final ManagedEntityRepository entityRepository;
    private final MentionRepository mentionRepository;
    private final SentimentAlertRepository alertRepository;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void scanForSpikes() {
        Instant now = clock.instant();
        List<ManagedEntity> entities = entityRepository.findAll();
        for (ManagedEntity entity : entities) {
            try {
                evaluateEntity(entity.getId(), now);
            } catch (Exception e) {
                log.error("Failed to evaluate sentiment spike for entity {}", entity.getId(), e);
            }
        }
    }

    void evaluateEntity(Long entityId, Instant now) {
        Instant windowStart = now.minus(ROLLING_WINDOW);
        long total = mentionRepository.countByManagedEntityIdAndPostDateBetween(entityId, windowStart, now);
        long negative = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entityId, Sentiment.NEGATIVE, windowStart, now);

        if (negative < MIN_ABSOLUTE_COUNT || total == 0) {
            return;
        }

        double currentRatio = (double) negative / total;
        double baselineRatio = computeBaselineRatio(entityId, now);
        if (baselineRatio <= 0.0 || currentRatio <= baselineRatio * SPIKE_MULTIPLIER) {
            return;
        }

        Instant dedupAfter = now.minus(DEDUP_WINDOW);
        boolean recentOpenAlertExists = alertRepository.existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
                entityId, SentimentAlert.Status.OPEN, dedupAfter);
        if (recentOpenAlertExists) {
            return;
        }

        SentimentAlert alert = SentimentAlert.builder()
                .managedEntityId(entityId)
                .triggeredAt(now)
                .kind(SentimentAlert.Kind.SPIKE)
                .currentValue(currentRatio)
                .baselineValue(baselineRatio)
                .status(SentimentAlert.Status.OPEN)
                .build();
        alertRepository.save(alert);
        log.info("Created SPIKE alert for entity {} (current={}, baseline={})",
                entityId, currentRatio, baselineRatio);
    }

    private double computeBaselineRatio(Long entityId, Instant now) {
        Instant baselineStart = now.minus(BASELINE_WINDOW);
        long baselineTotal = mentionRepository.countByManagedEntityIdAndPostDateBetween(
                entityId, baselineStart, now);
        if (baselineTotal == 0) {
            return 0.0;
        }
        long baselineNegative = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entityId, Sentiment.NEGATIVE, baselineStart, now);
        return (double) baselineNegative / baselineTotal;
    }
}
