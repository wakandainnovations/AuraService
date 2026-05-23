package com.aura.service.service;

import com.aura.service.dto.WhatsChangedResponse;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WhatsChangedService {

    private final MentionRepository mentionRepository;
    private final ManagedEntityRepository entityRepository;
    private final UserEntityViewService viewService;
    private final TopSpreaderLookupService spreaderLookup;
    private final UserRepository userRepository;

    public WhatsChangedResponse computeDelta(String username, Long entityId) {
        if (username == null || entityId == null) {
            return new WhatsChangedResponse();
        }
        Long userId = userRepository.findByUsername(username).map(User::getId).orElse(null);
        if (userId == null) {
            return new WhatsChangedResponse();
        }
        return computeDelta(userId, entityId);
    }

    public WhatsChangedResponse computeDelta(Long userId, Long entityId) {
        WhatsChangedResponse response = new WhatsChangedResponse();
        if (userId == null || entityId == null) {
            return response;
        }

        Optional<Instant> lastSeenOpt = viewService.findLastSeen(userId, entityId);
        if (lastSeenOpt.isEmpty()) {
            return response;
        }
        Instant lastSeen = lastSeenOpt.get();

        ManagedEntity entity = entityRepository.findById(entityId).orElse(null);
        if (entity == null) {
            return response;
        }

        response.setSentimentScoreDelta(computeScoreDelta(entityId, lastSeen));
        response.setNewMentionsCount(
                mentionRepository.countByManagedEntityIdAndPostDateAfter(entityId, lastSeen));
        response.setNewNegativeCount(
                mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(
                        entityId, Sentiment.NEGATIVE, lastSeen));
        response.setNewSuperSpreaderCount(computeNewSuperSpreaders(entity, lastSeen));
        response.setCompetitorDelta(computeCompetitorDeltas(entity, lastSeen));
        return response;
    }

    private double computeScoreDelta(Long entityId, Instant lastSeen) {
        double current = netScore(
                mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.POSITIVE),
                mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.NEGATIVE));
        double atLastSeen = netScore(
                mentionRepository.countByManagedEntityIdAndSentimentAndPostDateLessThanEqual(
                        entityId, Sentiment.POSITIVE, lastSeen),
                mentionRepository.countByManagedEntityIdAndSentimentAndPostDateLessThanEqual(
                        entityId, Sentiment.NEGATIVE, lastSeen));
        return current - atLastSeen;
    }

    private static double netScore(long positive, long negative) {
        return negative > 0 ? (double) positive / negative : 0.0;
    }

    private long computeNewSuperSpreaders(ManagedEntity entity, Instant lastSeen) {
        Long entityId = entity.getId();
        List<String> recentAuthorsList =
                mentionRepository.findDistinctAuthorsByEntityIdAndPostDateAfter(entityId, lastSeen);
        if (recentAuthorsList.isEmpty()) {
            return 0L;
        }
        Set<String> recentAuthors = new HashSet<>(recentAuthorsList);
        Set<String> priorAuthors = new HashSet<>(
                mentionRepository.findDistinctAuthorsByEntityIdAndPostDateLessThanEqual(entityId, lastSeen));
        recentAuthors.removeAll(priorAuthors);
        if (recentAuthors.isEmpty()) {
            return 0L;
        }

        Set<String> spreaders = unionOfSpreaders(entity);
        if (spreaders.isEmpty()) {
            return 0L;
        }
        recentAuthors.retainAll(spreaders);
        return recentAuthors.size();
    }

    private Set<String> unionOfSpreaders(ManagedEntity entity) {
        List<EntityKeyword> keywords = entity.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> union = new HashSet<>();
        for (EntityKeyword ek : keywords) {
            String keyword = ek.getKeyword();
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            union.addAll(spreaderLookup.getSpreaders(keyword));
        }
        return union;
    }

    private Map<String, Double> computeCompetitorDeltas(ManagedEntity entity, Instant lastSeen) {
        List<ManagedEntity> competitors = entity.getCompetitors();
        if (competitors == null || competitors.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> deltas = new LinkedHashMap<>();
        for (ManagedEntity competitor : competitors) {
            deltas.put(competitor.getName(), computeScoreDelta(competitor.getId(), lastSeen));
        }
        return deltas;
    }
}
