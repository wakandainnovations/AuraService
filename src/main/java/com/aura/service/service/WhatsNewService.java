package com.aura.service.service;

import com.aura.service.dto.WhatsNewCard;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.User;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WhatsNewService {

    public static final double COMPETITOR_DROP_THRESHOLD = 0.05;
    public static final double SENTIMENT_RISE_THRESHOLD = 0.05;
    public static final long NEGATIVE_SPIKE_THRESHOLD = 5L;
    public static final int MAX_CARDS = 5;

    public static final String KIND_COMPETITOR_DROP = "COMPETITOR_DROP";
    public static final String KIND_NEW_POSITIVE_SUPER_SPREADER = "NEW_POSITIVE_SUPER_SPREADER";
    public static final String KIND_SENTIMENT_RISE = "SENTIMENT_RISE";
    public static final String KIND_NEGATIVE_SPIKE = "NEGATIVE_SPIKE";

    private final MentionRepository mentionRepository;
    private final ManagedEntityRepository entityRepository;
    private final UserEntityViewService viewService;
    private final TopSpreaderLookupService spreaderLookup;
    private final UserRepository userRepository;
    private final Random random;

    @Autowired
    public WhatsNewService(MentionRepository mentionRepository,
                           ManagedEntityRepository entityRepository,
                           UserEntityViewService viewService,
                           TopSpreaderLookupService spreaderLookup,
                           UserRepository userRepository) {
        this(mentionRepository, entityRepository, viewService, spreaderLookup, userRepository, new Random());
    }

    public WhatsNewService(MentionRepository mentionRepository,
                           ManagedEntityRepository entityRepository,
                           UserEntityViewService viewService,
                           TopSpreaderLookupService spreaderLookup,
                           UserRepository userRepository,
                           Random random) {
        this.mentionRepository = mentionRepository;
        this.entityRepository = entityRepository;
        this.viewService = viewService;
        this.spreaderLookup = spreaderLookup;
        this.userRepository = userRepository;
        this.random = random;
    }

    public List<WhatsNewCard> getCards(String username, Long entityId) {
        if (username == null || entityId == null) {
            return List.of();
        }
        Long userId = userRepository.findByUsername(username).map(User::getId).orElse(null);
        if (userId == null) {
            return List.of();
        }
        return getCards(userId, entityId);
    }

    public List<WhatsNewCard> getCards(Long userId, Long entityId) {
        if (userId == null || entityId == null) {
            return List.of();
        }
        Optional<Instant> lastSeenOpt = viewService.findLastSeen(userId, entityId);
        if (lastSeenOpt.isEmpty()) {
            return List.of();
        }
        Instant lastSeen = lastSeenOpt.get();

        ManagedEntity entity = entityRepository.findById(entityId).orElse(null);
        if (entity == null) {
            return List.of();
        }

        List<WhatsNewCard> tier1 = competitorDropCards(entity, lastSeen);
        List<WhatsNewCard> tier2 = newPositiveSuperSpreaderCards(entity, lastSeen);
        List<WhatsNewCard> tier3 = sentimentRiseCards(entityId, lastSeen);
        List<WhatsNewCard> tier4 = negativeSpikeCards(entityId, lastSeen);

        Collections.shuffle(tier1, random);
        Collections.shuffle(tier2, random);
        Collections.shuffle(tier3, random);
        Collections.shuffle(tier4, random);

        List<WhatsNewCard> ordered = new ArrayList<>();
        ordered.addAll(tier1);
        ordered.addAll(tier2);
        ordered.addAll(tier3);
        ordered.addAll(tier4);

        if (ordered.size() <= MAX_CARDS) {
            return ordered;
        }
        return new ArrayList<>(ordered.subList(0, MAX_CARDS));
    }

    private List<WhatsNewCard> competitorDropCards(ManagedEntity entity, Instant lastSeen) {
        List<ManagedEntity> competitors = entity.getCompetitors();
        if (competitors == null || competitors.isEmpty()) {
            return new ArrayList<>();
        }
        List<WhatsNewCard> cards = new ArrayList<>();
        for (ManagedEntity competitor : competitors) {
            double delta = scoreDelta(competitor.getId(), lastSeen);
            if (delta >= -COMPETITOR_DROP_THRESHOLD) {
                continue;
            }
            List<Long> evidence = recentMentionIds(competitor.getId(), Sentiment.NEGATIVE, lastSeen);
            String headline = String.format(
                    "%s's sentiment dropped %.2f since your last visit",
                    competitor.getName(), Math.abs(delta));
            cards.add(new WhatsNewCard(KIND_COMPETITOR_DROP, headline, delta, evidence));
        }
        return cards;
    }

    private List<WhatsNewCard> newPositiveSuperSpreaderCards(ManagedEntity entity, Instant lastSeen) {
        Long entityId = entity.getId();
        List<String> recentAuthorsList =
                mentionRepository.findDistinctAuthorsByEntityIdAndPostDateAfter(entityId, lastSeen);
        if (recentAuthorsList.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> newAuthors = new HashSet<>(recentAuthorsList);
        Set<String> priorAuthors = new HashSet<>(
                mentionRepository.findDistinctAuthorsByEntityIdAndPostDateLessThanEqual(entityId, lastSeen));
        newAuthors.removeAll(priorAuthors);
        if (newAuthors.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> spreaders = unionOfSpreaders(entity);
        if (spreaders.isEmpty()) {
            return new ArrayList<>();
        }
        newAuthors.retainAll(spreaders);
        if (newAuthors.isEmpty()) {
            return new ArrayList<>();
        }
        List<WhatsNewCard> cards = new ArrayList<>();
        for (String author : newAuthors) {
            List<Mention> positives = mentionRepository
                    .findTop3ByManagedEntityIdAndAuthorAndSentimentAndPostDateAfterOrderByPostDateDesc(
                            entityId, author, Sentiment.POSITIVE, lastSeen);
            if (positives.isEmpty()) {
                continue;
            }
            List<Long> evidence = positives.stream().map(Mention::getId).collect(Collectors.toList());
            String headline = String.format(
                    "%s — a new super-spreader — is posting positively about %s",
                    author, entity.getName());
            cards.add(new WhatsNewCard(
                    KIND_NEW_POSITIVE_SUPER_SPREADER, headline, (double) positives.size(), evidence));
        }
        return cards;
    }

    private List<WhatsNewCard> sentimentRiseCards(Long entityId, Instant lastSeen) {
        double delta = scoreDelta(entityId, lastSeen);
        if (delta <= SENTIMENT_RISE_THRESHOLD) {
            return new ArrayList<>();
        }
        List<Long> evidence = recentMentionIds(entityId, Sentiment.POSITIVE, lastSeen);
        String headline = String.format(
                "Sentiment climbed %.2f since your last visit", delta);
        List<WhatsNewCard> cards = new ArrayList<>();
        cards.add(new WhatsNewCard(KIND_SENTIMENT_RISE, headline, delta, evidence));
        return cards;
    }

    private List<WhatsNewCard> negativeSpikeCards(Long entityId, Instant lastSeen) {
        long newNegative = mentionRepository
                .countByManagedEntityIdAndSentimentAndPostDateAfter(entityId, Sentiment.NEGATIVE, lastSeen);
        if (newNegative < NEGATIVE_SPIKE_THRESHOLD) {
            return new ArrayList<>();
        }
        List<Long> evidence = recentMentionIds(entityId, Sentiment.NEGATIVE, lastSeen);
        String headline = String.format(
                "%d new negative mentions since your last visit", newNegative);
        List<WhatsNewCard> cards = new ArrayList<>();
        cards.add(new WhatsNewCard(KIND_NEGATIVE_SPIKE, headline, (double) newNegative, evidence));
        return cards;
    }

    private double scoreDelta(Long entityId, Instant lastSeen) {
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

    private List<Long> recentMentionIds(Long entityId, Sentiment sentiment, Instant lastSeen) {
        return mentionRepository
                .findTop3ByManagedEntityIdAndSentimentAndPostDateAfterOrderByPostDateDesc(
                        entityId, sentiment, lastSeen)
                .stream()
                .map(Mention::getId)
                .collect(Collectors.toList());
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
}
