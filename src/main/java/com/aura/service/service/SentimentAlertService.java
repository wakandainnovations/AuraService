package com.aura.service.service;

import com.aura.service.alert.AlertDispatcher;
import com.aura.service.entity.AlertRule;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.AlertRuleRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AlertRuleRepository alertRuleRepository;
    private final TopSpreaderLookupService spreaderLookup;
    private final MovieBuffLookupService movieBuffLookup;
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
        if (baselineRatio <= 0.0) {
            return;
        }

        Instant dedupAfter = now.minus(DEDUP_WINDOW);
        List<AlertRule> rules = alertRuleRepository.findApplicable(SentimentAlert.Kind.SPIKE, entityId);

        if (rules.isEmpty()) {
            // No user rule: fall back to the default multiplier threshold and
            // raise an un-owned alert, preserving the original behaviour.
            if (currentRatio > baselineRatio * SPIKE_MULTIPLIER) {
                maybeCreateSpikeAlert(entityId, null, currentRatio, baselineRatio, now, dedupAfter);
            }
            return;
        }

        // A user may have both an entity-specific and a wildcard rule; collapse
        // to the most sensitive (lowest) rise threshold per user so each owner
        // gets at most one alert.
        Map<Long, Double> riseByUser = new LinkedHashMap<>();
        for (AlertRule rule : rules) {
            riseByUser.merge(rule.getUserId(), rule.getThreshold(), Math::min);
        }
        for (Map.Entry<Long, Double> entry : riseByUser.entrySet()) {
            // threshold = minimum absolute rise in negative-sentiment ratio over baseline
            if (currentRatio - baselineRatio >= entry.getValue()) {
                maybeCreateSpikeAlert(entityId, entry.getKey(), currentRatio, baselineRatio, now, dedupAfter);
            }
        }
    }

    private void maybeCreateSpikeAlert(Long entityId, Long ownerUserId,
                                       double currentRatio, double baselineRatio,
                                       Instant now, Instant dedupAfter) {
        if (alertRepository.existsRecentOpenForOwner(
                entityId, SentimentAlert.Status.OPEN, dedupAfter, ownerUserId)) {
            return;
        }
        SentimentAlert alert = SentimentAlert.builder()
                .managedEntityId(entityId)
                .ownerUserId(ownerUserId)
                .triggeredAt(now)
                .kind(SentimentAlert.Kind.SPIKE)
                .currentValue(currentRatio)
                .baselineValue(baselineRatio)
                .status(SentimentAlert.Status.OPEN)
                .build();
        SentimentAlert saved = alertRepository.save(alert);
        log.info("Created SPIKE alert for entity {} owner {} (current={}, baseline={})",
                entityId, ownerUserId, currentRatio, baselineRatio);
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
        String author = mention.getAuthor();
        if (author == null || author.isBlank()) {
            return;
        }
        if (mention.getManagedEntities() == null) {
            return;
        }
        // A post can be attributed to several entities; evaluate the alert for each independently
        // since each entity has its own keywords, rules and owners.
        for (ManagedEntity entity : mention.getManagedEntities()) {
            evaluateInfluencerNegativeForEntity(mention, entity, author);
        }
    }

    private void evaluateInfluencerNegativeForEntity(Mention mention, ManagedEntity entity, String author) {
        if (entity == null) {
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
            boolean isMovieBuff = movieBuffLookup.getMovieBuffs(keyword).stream()
                    .anyMatch(buff -> author.equals(buff.author()));
            if (isMovieBuff) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            return;
        }

        List<AlertRule> rules = alertRuleRepository.findApplicable(
                SentimentAlert.Kind.INFLUENCER_NEGATIVE, entity.getId());
        if (rules.isEmpty()) {
            // No user rule: fall back to a single un-owned alert (original behaviour).
            maybeCreateInfluencerAlert(entity.getId(), null, mention, author);
            return;
        }
        rules.stream()
                .map(AlertRule::getUserId)
                .distinct()
                .forEach(ownerUserId -> maybeCreateInfluencerAlert(entity.getId(), ownerUserId, mention, author));
    }

    private void maybeCreateInfluencerAlert(Long entityId, Long ownerUserId, Mention mention, String author) {
        if (alertRepository.existsByKindAndSourceMentionIdForOwner(
                SentimentAlert.Kind.INFLUENCER_NEGATIVE, mention.getId(), ownerUserId)) {
            return;
        }
        SentimentAlert alert = SentimentAlert.builder()
                .managedEntityId(entityId)
                .ownerUserId(ownerUserId)
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
        log.info("Created INFLUENCER_NEGATIVE alert for entity {} mention {} author {} owner {}",
                entityId, mention.getId(), author, ownerUserId);
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
