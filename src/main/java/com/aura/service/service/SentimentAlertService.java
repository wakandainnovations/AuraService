package com.aura.service.service;

import com.aura.service.alert.AlertDispatcher;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
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
import java.util.Set;

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
    private final TopSpreaderLookupService spreaderLookup;
    private final AlertDispatcher alertDispatcher;
    private final Clock clock;

    private volatile Long influencerWatermark;

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
        SentimentAlert saved = alertRepository.save(alert);
        log.info("Created SPIKE alert for entity {} (current={}, baseline={})",
                entityId, currentRatio, baselineRatio);
        alertDispatcher.dispatch(saved);
    }

    @Scheduled(fixedDelayString = "PT1M")
    @Transactional
    public void scanForInfluencerNegatives() {
        long since = currentInfluencerWatermark();
        List<Mention> newNegatives = mentionRepository
                .findByIdGreaterThanAndSentimentOrderByIdAsc(since, Sentiment.NEGATIVE);
        if (newNegatives.isEmpty()) {
            return;
        }

        long maxSeen = since;
        for (Mention mention : newNegatives) {
            try {
                evaluateInfluencerNegative(mention);
            } catch (Exception e) {
                log.error("Failed to evaluate influencer-negative for mention {}", mention.getId(), e);
            }
            if (mention.getId() > maxSeen) {
                maxSeen = mention.getId();
            }
        }
        influencerWatermark = maxSeen;
    }

    void evaluateInfluencerNegative(Mention mention) {
        ManagedEntity entity = mention.getManagedEntity();
        if (entity == null) {
            return;
        }
        String author = mention.getAuthor();
        if (author == null || author.isBlank()) {
            return;
        }
        List<EntityKeyword> keywords = entity.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return;
        }

        boolean matched = false;
        for (EntityKeyword ek : keywords) {
            String keyword = ek.getKeyword();
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            Set<String> spreaders = spreaderLookup.getSpreaders(keyword);
            if (spreaders.contains(author)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            return;
        }

        if (alertRepository.existsByKindAndSourceMentionId(
                SentimentAlert.Kind.INFLUENCER_NEGATIVE, mention.getId())) {
            return;
        }

        SentimentAlert alert = SentimentAlert.builder()
                .managedEntityId(entity.getId())
                .triggeredAt(clock.instant())
                .kind(SentimentAlert.Kind.INFLUENCER_NEGATIVE)
                .currentValue(0.0)
                .baselineValue(0.0)
                .status(SentimentAlert.Status.OPEN)
                .sourceMentionId(mention.getId())
                .matchedAuthor(author)
                .permalink(mention.getPermalink())
                .build();
        SentimentAlert saved = alertRepository.save(alert);
        log.info("Created INFLUENCER_NEGATIVE alert for entity {} mention {} author {}",
                entity.getId(), mention.getId(), author);
        alertDispatcher.dispatch(saved);
    }

    private long currentInfluencerWatermark() {
        Long current = influencerWatermark;
        if (current != null) {
            return current;
        }
        Long persistent = alertRepository.findMaxSourceMentionIdByKind(
                SentimentAlert.Kind.INFLUENCER_NEGATIVE);
        long initial = persistent != null ? persistent : mentionRepository.findMaxId();
        influencerWatermark = initial;
        return initial;
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
