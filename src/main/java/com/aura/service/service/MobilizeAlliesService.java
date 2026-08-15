package com.aura.service.service;

import com.aura.service.dto.AllyRecommendation;
import com.aura.service.dto.MentionResponse;
import com.aura.service.dto.MobilizeAlliesResponse;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Sentiment;
import com.aura.service.proxy.TtlCache;
import com.aura.service.repository.MentionRepository;
import com.aura.service.service.TopSpreaderLookupService.SpreaderProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes "mobilize allies" recommendations and caches them so the user-facing
 * action endpoint can respond quickly. The cache can be warmed ahead of time
 * (see {@link #warm(Long)}) when a user views a mention, so the eventual click
 * on "Mobilize Allies" is a cache hit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MobilizeAlliesService {

    static final int ALLY_LIMIT = 10;
    static final Duration ALLY_CACHE_TTL = Duration.ofMinutes(5);

    private final MentionRepository mentionRepository;
    private final TopSpreaderLookupService spreaderLookup;
    private final LLMService llmService;
    private final ImpressionsResolver impressionsResolver;

    @Value("${llm.prompt.generate.ally.dm}")
    private String allyDmPromptTemplate;

    private final TtlCache<MobilizeAlliesResponse> allyCache = new TtlCache<>(1024);

    private static String cacheKey(ManagedEntity entity, Mention mention) {
        return entity.getId() + ":" + mention.getId();
    }

    /**
     * Returns ally recommendations for the mention, computing and caching them on a miss.
     * Does NOT record a mobilize action — callers that represent an explicit user action
     * are responsible for recording it.
     */
    @Transactional(readOnly = true)
    public MobilizeAlliesResponse getOrComputeAllies(Mention mention) {
        String key = cacheKey(mention.getPrimaryManagedEntity(), mention);
        MobilizeAlliesResponse cached = allyCache.get(key);
        if (cached != null) {
            return cached;
        }
        MobilizeAlliesResponse response = computeAllies(mention);
        allyCache.put(key, response, ALLY_CACHE_TTL.toNanos());
        return response;
    }

    /**
     * Best-effort background warm of the ally cache, triggered when a user views a mention.
     * Runs on a separate thread (outside the request, so OSIV does not apply), hence it opens
     * its own read-only transaction so the entity's lazy keyword collection can be loaded.
     * Never throws and never records a mobilize action.
     */
    @Async
    @Transactional(readOnly = true)
    public void warm(Long mentionId) {
        try {
            Mention mention = mentionRepository.findById(mentionId).orElse(null);
            if (mention == null) {
                return;
            }
            String key = cacheKey(mention.getPrimaryManagedEntity(), mention);
            if (allyCache.get(key) != null) {
                return;
            }
            allyCache.put(key, computeAllies(mention), ALLY_CACHE_TTL.toNanos());
        } catch (Exception e) {
            log.debug("Ally cache warm failed for mention {}: {}", mentionId, e.toString());
        }
    }

    private MobilizeAlliesResponse computeAllies(Mention mention) {
        ManagedEntity entity = mention.getPrimaryManagedEntity();

        List<String> keywords = new ArrayList<>();
        if (entity.getKeywords() != null) {
            for (EntityKeyword ek : entity.getKeywords()) {
                if (ek != null && ek.getKeyword() != null && !ek.getKeyword().isBlank()) {
                    keywords.add(ek.getKeyword());
                }
            }
        }

        Map<String, SpreaderProfile> candidates = fetchSpreaderProfiles(keywords);
        if (candidates.isEmpty()) {
            return new MobilizeAlliesResponse(toMentionResponse(mention), List.of());
        }

        Map<String, Long> positiveCounts = filterPredominantlyPositive(entity.getId(), candidates.keySet());

        // Ranked by AuraMath's total_views (the only real reach proxy top-50-spreaders provides),
        // tiebroken by this platform's own positive-mention count. influenceTier is never populated
        // by that endpoint, so it is not used for ranking.
        List<SpreaderProfile> ranked = candidates.values().stream()
                .filter(p -> positiveCounts.containsKey(p.globalUserId()))
                .sorted(Comparator
                        .comparingLong(SpreaderProfile::totalViews).reversed()
                        .thenComparing((SpreaderProfile p) ->
                                positiveCounts.getOrDefault(p.globalUserId(), 0L), Comparator.reverseOrder())
                        .thenComparing(SpreaderProfile::globalUserId))
                .limit(ALLY_LIMIT)
                .toList();

        String entityName = entity.getName();
        String mentionContent = mention.getContent();
        List<AllyRecommendation> allies = Flux.fromIterable(ranked)
                .flatMapSequential(p -> Mono.fromCallable(() -> new AllyRecommendation(
                                p.globalUserId(),
                                p.primaryPlatform(),
                                p.influenceTier(),
                                generateAllyDm(entityName, mentionContent, p)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .blockOptional()
                .orElse(List.of());

        return new MobilizeAlliesResponse(toMentionResponse(mention), allies);
    }

    private Map<String, SpreaderProfile> fetchSpreaderProfiles(List<String> keywords) {
        if (keywords.isEmpty()) {
            return Map.of();
        }
        List<List<SpreaderProfile>> perKeyword = Flux.fromIterable(keywords)
                .flatMap(kw -> Mono.fromCallable(() -> spreaderLookup.getSpreaderProfiles(kw))
                        .subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .blockOptional()
                .orElse(List.of());

        Map<String, SpreaderProfile> deduped = new LinkedHashMap<>();
        for (List<SpreaderProfile> profiles : perKeyword) {
            for (SpreaderProfile p : profiles) {
                if (p.globalUserId() == null || p.globalUserId().isBlank()) {
                    continue;
                }
                deduped.merge(p.globalUserId(), p, (existing, incoming) -> new SpreaderProfile(
                        existing.globalUserId(),
                        existing.primaryPlatform() != null ? existing.primaryPlatform() : incoming.primaryPlatform(),
                        existing.influenceTier() != null ? existing.influenceTier() : incoming.influenceTier(),
                        Math.max(existing.totalViews(), incoming.totalViews()),
                        existing.profileUrl() != null ? existing.profileUrl() : incoming.profileUrl()
                ));
            }
        }
        return deduped;
    }

    private Map<String, Long> filterPredominantlyPositive(Long entityId, Set<String> authors) {
        if (authors.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = mentionRepository.countSentimentByAuthorsForEntity(entityId, authors);
        Map<String, EnumMap<Sentiment, Long>> byAuthor = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String author = (String) row[0];
            Sentiment sentiment = (Sentiment) row[1];
            long count = ((Number) row[2]).longValue();
            byAuthor.computeIfAbsent(author, k -> new EnumMap<>(Sentiment.class))
                    .merge(sentiment, count, Long::sum);
        }
        Map<String, Long> positive = new LinkedHashMap<>();
        for (Map.Entry<String, EnumMap<Sentiment, Long>> e : byAuthor.entrySet()) {
            EnumMap<Sentiment, Long> counts = e.getValue();
            long pos = counts.getOrDefault(Sentiment.POSITIVE, 0L);
            long neg = counts.getOrDefault(Sentiment.NEGATIVE, 0L);
            long neu = counts.getOrDefault(Sentiment.NEUTRAL, 0L);
            if (pos > 0 && pos > neg && pos >= neu) {
                positive.put(e.getKey(), pos);
            }
        }
        return positive;
    }

    private String generateAllyDm(String entityName, String mentionContent, SpreaderProfile profile) {
        String prompt = allyDmPromptTemplate
                .replace("[Managed Entity]", nullSafe(entityName))
                .replace("[Ally Handle]", nullSafe(profile.globalUserId()))
                .replace("[Ally Platform]", nullSafe(profile.primaryPlatform()))
                .replace("[Ally Tier]", nullSafe(profile.influenceTier()))
                .replace("[Mention Content]", nullSafe(mentionContent));

        String generated = llmService.generateReply(prompt);
        if (generated == null) {
            return "";
        }
        int firstQuote = generated.indexOf('"');
        int lastQuote = generated.lastIndexOf('"');
        if (firstQuote != -1 && lastQuote != -1 && firstQuote != lastQuote) {
            generated = generated.substring(firstQuote + 1, lastQuote);
        }
        return generated;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private MentionResponse toMentionResponse(Mention mention) {
        return new MentionResponse(
                mention.getId(),
                mention.getPrimaryManagedEntity().getId(),
                mention.getPlatform(),
                mention.getPostId(),
                mention.getContent(),
                mention.getAuthor(),
                mention.getPostDate(),
                mention.getSentiment(),
                mention.getPermalink(),
                mention.getSentimentScore(),
                impressionsResolver.resolveForMention(mention)
        );
    }
}
