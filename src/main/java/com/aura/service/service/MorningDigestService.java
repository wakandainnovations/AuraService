package com.aura.service.service;

import com.aura.service.alert.EmailChannel;
import com.aura.service.dto.WhatsChangedResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.UserEntityViewRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MorningDigestService {

    static final int DIGEST_HOUR = 8;

    /** How many impact highlights to lead the digest with — keep it focused on the top wins. */
    static final int MAX_IMPACT_HIGHLIGHTS = 3;

    private final UserRepository userRepository;
    private final UserEntityViewRepository viewRepository;
    private final ManagedEntityRepository entityRepository;
    private final WhatsChangedService whatsChangedService;
    private final WorkspaceImpactService workspaceImpactService;
    private final EmailChannel emailChannel;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *")
    public void sendMorningDigests() {
        List<User> users = userRepository.findAll();
        for (User user : users) {
            try {
                if (isDigestTime(user)) {
                    buildAndSend(user);
                }
            } catch (Exception e) {
                log.error("Morning digest failed for user {}", user.getUsername(), e);
            }
        }
    }

    boolean isDigestTime(User user) {
        ZoneId zone;
        try {
            zone = ZoneId.of(user.getTimezone());
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }
        ZonedDateTime now = clock.instant().atZone(zone);
        return now.getHour() == DIGEST_HOUR && now.getMinute() == 0;
    }

    void buildAndSend(User user) {
        List<Long> entityIds = viewRepository.findEntityIdsByUserId(user.getId());
        if (entityIds.isEmpty()) {
            return;
        }

        Map<String, WhatsChangedResponse> entries = new LinkedHashMap<>();
        for (Long entityId : entityIds) {
            WhatsChangedResponse delta = whatsChangedService.computeDelta(user.getId(), entityId);
            if (isEmpty(delta)) {
                continue;
            }
            String entityName = entityRepository.findById(entityId)
                    .map(ManagedEntity::getName)
                    .orElse("Entity #" + entityId);
            entries.put(entityName, delta);
        }

        if (entries.isEmpty()) {
            return;
        }

        String headline = pickHeadline(entries);
        String subject = "Your overnight Aura brief: " + headline;
        List<String> impactHighlights = topImpactHighlights(user);
        emailChannel.sendDigest(user, subject, entries, impactHighlights);
        log.info("Morning digest sent for user {} ({} entities)", user.getUsername(), entries.size());
    }

    /**
     * The top few "investment made visible" highlights for the user, or an empty list. Computing
     * impact must never sink the digest, so any failure here degrades to no highlights.
     */
    List<String> topImpactHighlights(User user) {
        try {
            List<String> highlights = workspaceImpactService.getImpact(user.getId()).getHighlights();
            if (highlights == null || highlights.isEmpty()) {
                return List.of();
            }
            return highlights.stream().limit(MAX_IMPACT_HIGHLIGHTS).toList();
        } catch (Exception e) {
            log.warn("Could not compute impact highlights for user {}", user.getUsername(), e);
            return List.of();
        }
    }

    String pickHeadline(Map<String, WhatsChangedResponse> entries) {
        String bestEntity = null;
        long bestScore = Long.MIN_VALUE;

        for (Map.Entry<String, WhatsChangedResponse> entry : entries.entrySet()) {
            WhatsChangedResponse delta = entry.getValue();
            long mentions = delta.getNewMentionsCount() != null ? delta.getNewMentionsCount() : 0;
            long negatives = delta.getNewNegativeCount() != null ? delta.getNewNegativeCount() : 0;
            long spreaders = delta.getNewSuperSpreaderCount() != null ? delta.getNewSuperSpreaderCount() : 0;

            if (spreaders > 0) {
                bestEntity = entry.getKey();
                break;
            }

            long score = negatives * 3 + mentions;
            if (score > bestScore) {
                bestScore = score;
                bestEntity = entry.getKey();
            }
        }

        if (bestEntity == null) {
            bestEntity = entries.keySet().iterator().next();
        }

        WhatsChangedResponse delta = entries.get(bestEntity);
        long spreaders = delta.getNewSuperSpreaderCount() != null ? delta.getNewSuperSpreaderCount() : 0;
        long negatives = delta.getNewNegativeCount() != null ? delta.getNewNegativeCount() : 0;
        long mentions = delta.getNewMentionsCount() != null ? delta.getNewMentionsCount() : 0;

        if (spreaders > 0) {
            return bestEntity + " has " + spreaders + " new super-spreader mention" + (spreaders > 1 ? "s" : "");
        }
        if (negatives > 0) {
            return bestEntity + " picked up " + negatives + " negative mention" + (negatives > 1 ? "s" : "");
        }
        return bestEntity + " has " + mentions + " new mention" + (mentions > 1 ? "s" : "");
    }

    private static boolean isEmpty(WhatsChangedResponse delta) {
        long mentions = delta.getNewMentionsCount() != null ? delta.getNewMentionsCount() : 0;
        return mentions == 0
                && (delta.getSentimentScoreDelta() == null || delta.getSentimentScoreDelta() == 0.0);
    }
}
