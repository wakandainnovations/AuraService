package com.aura.service.service;

import com.aura.service.dto.SituationRecommendationResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.SituationRecommendationCache;
import com.aura.service.enums.ReviewAspectCategory;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.SituationRecommendationCacheRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Social Buzz Situation" panel: given a movie's real last-7-days/last-24h post activity - not just a
 * sustained negativity trend but also a same-day burst, and just as much the case where there is no
 * activity at all (see this class's own handling of {@code hasSocialActivity}) - builds one JSON payload
 * of real, server-computed facts and asks {@link LLMService} for a single recommended action grounded in
 * how a real movie in the past handled a comparable situation.
 *
 * <p>Unlike {@link RecommendedActionsService}/{@link RecommendedActionCandidateService}, whose whole
 * design is that the LLM only ever selects/phrases around fully server-computed candidates and never
 * recalls real-world facts from its own training, this feature's entire point is the opposite: no table
 * in this platform's schema records how a real movie's team responded to a real social-media crisis or a
 * quiet pre-release period, so naming that precedent and what its team did is necessarily left to the
 * LLM's own knowledge - see the {@code ALLOWED EXCEPTION} carve-out in {@code
 * llm.prompt.generate.situation.recommended.action}. Every other fact this service sends the LLM (post
 * counts, sentiment counts, negativity themes, post excerpts, view counts, comparable-movie figures)
 * is real and server-computed, and the prompt's CRITICAL GROUNDING RULE restricts the LLM to those
 * numbers for anything about THIS movie.
 *
 * <p>Two data sources, deliberately not one: raw post VOLUME ({@code postsLast7Days}/{@code
 * postsLast24Hours}/{@code hasSocialActivity}) is read straight off the four platform tables via {@link
 * MentionRepository#countRawPostsForEntitySince} - {@code mentions} is populated by an ingestion
 * pipeline external to this codebase and can lag those tables by a meaningful margin, which would
 * silently understate or miss a same-day burst on a feature whose whole point is reacting to one.
 * Sentiment-specific counts, negativity themes, and post excerpts stay sourced from {@code mentions}
 * and inherit its lag - the raw tables carry no reliable POSITIVE/NEGATIVE/NEUTRAL signal to compute
 * those from instead (see that same javadoc for why). {@code ownTotalViews}/comparable-movie views
 * already read the raw tables directly too (see {@link MentionRepository#findTotalViewsForEntity}) but
 * still attribute a raw row to an entity via {@code mentions}/{@code mention_entities}, so they inherit
 * the same lag as the sentiment-side data - only the two counts above get the lag-free, keyword-matched
 * attribution.
 *
 * <p>Cached per entity in {@link SituationRecommendationCache} for {@link #CACHE_TTL} - short relative
 * to {@link RecommendedActionsService}'s 24h cycle, since a same-day negativity burst is exactly the
 * kind of thing this panel needs to reflect same-day, not on tomorrow's scheduled refresh. Regenerated
 * synchronously on a stale/missing cache row or an explicit {@code refresh=true}; there is no background
 * scheduler here (unlike {@code RecommendedActionsService}) since a single LLM call on an already-narrow
 * TTL is cheap enough to run inline.
 */
@Slf4j
@Service
public class SituationRecommendationService {

    private static final String MOVIE_TYPE = "MOVIE";
    private static final String SITUATION_DATA_PLACEHOLDER = "[Situation Data]";

    static final Duration CACHE_TTL = Duration.ofHours(4);
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);
    private static final Duration TWENTY_FOUR_HOURS = Duration.ofHours(24);

    // A same-day negative-post count only reads as a "burst" (Instruction 2 in the prompt, timely
    // action over generic advice) when it's both a meaningful multiple of the prior 6 days' own daily
    // average AND at least this many posts in absolute terms - guards against "2 negative posts today
    // vs 0 yesterday" reading as a burst purely off a tiny denominator, same reasoning as
    // RecommendedActionCandidateServiceImpl's VIEW_GAP_MIN_ABSOLUTE_VIEWS_WHEN_OWN_ZERO.
    static final double BURST_MULTIPLIER = 2.0;
    static final long BURST_MIN_ABSOLUTE_NEGATIVE_POSTS = 5;

    private static final int NEGATIVITY_THEME_LIMIT = 5;
    private static final int EXCERPT_LIMIT = 5;
    private static final int EXCERPT_MAX_CHARS = 240;
    private static final int COMPARABLE_MOVIES_LIMIT = 5;

    private final ManagedEntityRepository managedEntityRepository;
    private final MentionRepository mentionRepository;
    private final MoviesDataCollectionQueryService moviesDataQueryService;
    private final SituationRecommendationCacheRepository cacheRepository;
    private final LLMService llmService;
    private final Clock clock;
    // JavaTimeModule registered explicitly - a bare `new ObjectMapper()` (unlike the Spring-managed
    // bean used for HTTP responses) does not auto-discover jsr310 support, and this mapper serializes
    // generatedAt (an Instant) into the cached JSON blob.
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Value("${llm.prompt.generate.situation.recommended.action}")
    private String llmPrompt;

    public SituationRecommendationService(
            ManagedEntityRepository managedEntityRepository,
            MentionRepository mentionRepository,
            MoviesDataCollectionQueryService moviesDataQueryService,
            SituationRecommendationCacheRepository cacheRepository,
            LLMService llmService,
            Clock clock) {
        this.managedEntityRepository = managedEntityRepository;
        this.mentionRepository = mentionRepository;
        this.moviesDataQueryService = moviesDataQueryService;
        this.cacheRepository = cacheRepository;
        this.llmService = llmService;
        this.clock = clock;
    }

    @Transactional
    public SituationRecommendationResponse getSituationRecommendation(Long entityId, boolean refresh) {
        var cached = cacheRepository.findByEntityId(entityId);
        if (cached.isPresent() && !refresh && !isStale(cached.get())) {
            SituationRecommendationResponse parsed = readCached(cached.get());
            if (parsed != null) {
                return parsed;
            }
        }
        SituationRecommendationResponse response = generate(entityId);
        persist(entityId, response);
        return response;
    }

    private boolean isStale(SituationRecommendationCache row) {
        return Duration.between(row.getGeneratedAt(), clock.instant()).compareTo(CACHE_TTL) > 0;
    }

    private SituationRecommendationResponse readCached(SituationRecommendationCache row) {
        try {
            return objectMapper.readValue(row.getResponseJson(), SituationRecommendationResponse.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize cached situation recommendation for entity {} — regenerating",
                    row.getEntityId(), e);
            return null;
        }
    }

    private void persist(Long entityId, SituationRecommendationResponse response) {
        try {
            SituationRecommendationCache row = cacheRepository.findByEntityId(entityId)
                    .orElseGet(SituationRecommendationCache::new);
            row.setEntityId(entityId);
            row.setResponseJson(objectMapper.writeValueAsString(response));
            row.setGeneratedAt(response.getGeneratedAt());
            cacheRepository.save(row);
        } catch (Exception e) {
            log.error("Failed to persist situation recommendation cache for entity {}", entityId, e);
        }
    }

    private SituationRecommendationResponse generate(Long entityId) {
        ManagedEntity entity = managedEntityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        Instant now = clock.instant();
        Instant sevenDaysAgo = now.minus(SEVEN_DAYS);
        Instant oneDayAgo = now.minus(TWENTY_FOUR_HOURS);

        SituationRecommendationResponse r = new SituationRecommendationResponse();
        r.setEntityId(entityId);
        r.setEntityName(entity.getName());
        r.setGenre(entity.getGenre());
        r.setLanguage(entity.getLanguage());
        r.setIndustry(entity.getIndustry());
        r.setDaysToRelease(daysToRelease(entity.getReleaseDate(), now));

        // Raw post VOLUME comes straight from the four platform tables (see
        // MentionRepository#countRawPostsForEntitySince's own javadoc for why) - mentions can lag them
        // by days/weeks. Sentiment-specific counts below stay on mentions: the raw tables carry no
        // reliable POSITIVE/NEGATIVE/NEUTRAL signal to substitute (see that same javadoc).
        r.setPostsLast7Days(mentionRepository.countRawPostsForEntitySince(entityId, sevenDaysAgo));
        r.setPositiveCountLast7Days(mentionRepository
                .countByManagedEntityIdAndSentimentAndPostDateAfter(entityId, Sentiment.POSITIVE, sevenDaysAgo));
        r.setNegativeCountLast7Days(mentionRepository
                .countByManagedEntityIdAndSentimentAndPostDateAfter(entityId, Sentiment.NEGATIVE, sevenDaysAgo));
        r.setNeutralCountLast7Days(mentionRepository
                .countByManagedEntityIdAndSentimentAndPostDateAfter(entityId, Sentiment.NEUTRAL, sevenDaysAgo));

        r.setPostsLast24Hours(mentionRepository.countRawPostsForEntitySince(entityId, oneDayAgo));
        r.setPositiveCountLast24Hours(mentionRepository
                .countByManagedEntityIdAndSentimentAndPostDateAfter(entityId, Sentiment.POSITIVE, oneDayAgo));
        r.setNegativeCountLast24Hours(mentionRepository
                .countByManagedEntityIdAndSentimentAndPostDateAfter(entityId, Sentiment.NEGATIVE, oneDayAgo));

        r.setHasSocialActivity(r.getPostsLast7Days() > 0);
        r.setNegativeBurstDetected(isNegativeBurst(r.getNegativeCountLast7Days(), r.getNegativeCountLast24Hours()));

        r.setNegativityThemes(negativityThemes(entityId, sevenDaysAgo));
        r.setKeyNegativePoints(excerpts(entityId, Sentiment.NEGATIVE, sevenDaysAgo));
        r.setKeyPositivePoints(excerpts(entityId, Sentiment.POSITIVE, sevenDaysAgo));

        r.setOwnTotalViews(mentionRepository.findTotalViewsForEntity(entityId));

        populateComparableMovies(entity, r);

        applyLlmRecommendation(entity, r);

        r.setGeneratedAt(now);
        return r;
    }

    // Signed day-offset of "today" from entity.releaseDate (negative = before release, positive =
    // after), same sign convention as RecommendedActionsService#todayOffsetFromRelease - lets the LLM
    // (and the response) distinguish a pre-release buzz-building situation from a post-release
    // reception one, which call for very different recommended actions and precedents.
    private Integer daysToRelease(LocalDate releaseDate, Instant now) {
        if (releaseDate == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(releaseDate, LocalDate.ofInstant(now, clock.getZone()));
    }

    // A same-day burst is judged against the PRIOR 6 days' own daily average, not the full 7-day
    // average - the 24h window is already inside the 7-day one, so folding today's own count into its
    // own baseline would understate how unusual it is.
    private static boolean isNegativeBurst(long negativeLast7Days, long negativeLast24Hours) {
        if (negativeLast24Hours < BURST_MIN_ABSOLUTE_NEGATIVE_POSTS) {
            return false;
        }
        long priorSixDaysNegative = Math.max(0, negativeLast7Days - negativeLast24Hours);
        double avgDailyPriorSixDays = priorSixDaysNegative / 6.0;
        return negativeLast24Hours >= avgDailyPriorSixDays * BURST_MULTIPLIER;
    }

    private List<String> negativityThemes(Long entityId, Instant since) {
        List<Object[]> rows = mentionRepository
                .findReviewAspectCountsForEntityAndSentimentSince(entityId, Sentiment.NEGATIVE, since);
        List<String> themes = new ArrayList<>();
        for (Object[] row : rows) {
            if (themes.size() >= NEGATIVITY_THEME_LIMIT) {
                break;
            }
            ReviewAspectCategory category = (ReviewAspectCategory) row[0];
            long count = ((Number) row[1]).longValue();
            themes.add(String.format(Locale.ROOT, "%s (%d post%s)", category.name(), count, count == 1 ? "" : "s"));
        }
        return themes;
    }

    private List<String> excerpts(Long entityId, Sentiment sentiment, Instant since) {
        List<Mention> mentions = mentionRepository.findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(
                entityId, sentiment, since, PageRequest.of(0, EXCERPT_LIMIT));
        List<String> excerpts = new ArrayList<>();
        for (Mention m : mentions) {
            String content = m.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String trimmed = content.strip();
            excerpts.add(trimmed.length() > EXCERPT_MAX_CHARS
                    ? trimmed.substring(0, EXCERPT_MAX_CHARS) + "..."
                    : trimmed);
        }
        return excerpts;
    }

    // Budget-scoped (+/-50%, same range as RecommendedActionCandidateServiceImpl.BUDGET_RANGE_FRACTION)
    // when this movie has a real, disclosed budget; otherwise falls back to a genre+language pool of
    // this platform's own tracked movies - the small/independent productions with no budget on file are
    // exactly the ones a budget-scoped comparison would otherwise silently skip. Also pulls a real
    // genre+language(+budget) revenue-comp average off movies_data_collection when available, same
    // source RecommendedActionCandidateServiceImpl.comparableBudgetCandidates uses.
    private void populateComparableMovies(ManagedEntity entity, SituationRecommendationResponse r) {
        boolean budgetScoped = RecommendedActionCandidateServiceImpl.hasRealBudget(entity.getBudget());
        r.setComparableMoviesBudgetScoped(budgetScoped);

        double minBudget = budgetScoped
                ? entity.getBudget() * (1 - RecommendedActionCandidateServiceImpl.BUDGET_RANGE_FRACTION) : 0;
        double maxBudget = budgetScoped
                ? entity.getBudget() * (1 + RecommendedActionCandidateServiceImpl.BUDGET_RANGE_FRACTION)
                : Double.MAX_VALUE;

        List<ManagedEntity> pool;
        if (budgetScoped) {
            pool = managedEntityRepository
                    .findByTypeAndBudgetBetweenAndIdNot(MOVIE_TYPE, minBudget, maxBudget, entity.getId()).stream()
                    .filter(m -> RecommendedActionCandidateServiceImpl.hasRealBudget(m.getBudget()))
                    .toList();
        } else {
            Set<String> genreTokens = MoviesDataCollectionQueryServiceImpl.tokenizeGenre(entity.getGenre());
            pool = genreTokens.isEmpty() || entity.getLanguage() == null
                    ? List.of()
                    : managedEntityRepository.findByTypeAndLanguageIgnoreCase(MOVIE_TYPE, entity.getLanguage())
                            .stream()
                            .filter(m -> !m.getId().equals(entity.getId()))
                            .filter(m -> MoviesDataCollectionQueryServiceImpl.genreOverlaps(genreTokens, m.getGenre()))
                            .toList();
        }

        r.setComparableMovies(topComparableMoviesByViews(pool));

        List<Object[]> compRows = moviesDataQueryService
                .findGenreLanguageBudgetComps(entity.getGenre(), entity.getLanguage(), minBudget, maxBudget);
        if (!compRows.isEmpty() && compRows.get(0)[0] != null && compRows.get(0)[1] != null) {
            Object[] row = compRows.get(0);
            r.setComparableSampleCount(((Number) row[0]).longValue());
            r.setComparableAvgRevenue(((Number) row[1]).doubleValue());
        }
    }

    private List<SituationRecommendationResponse.ComparableMovieView> topComparableMoviesByViews(
            List<ManagedEntity> pool) {
        if (pool.isEmpty()) {
            return List.of();
        }
        Map<Long, ManagedEntity> byId = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        for (ManagedEntity m : pool) {
            byId.put(m.getId(), m);
            ids.add(m.getId());
        }
        Map<Long, Long> viewsById = new LinkedHashMap<>();
        for (Object[] row : mentionRepository.findTotalViewsForEntities(ids)) {
            Long id = ((Number) row[0]).longValue();
            long views = row[1] == null ? 0L : ((Number) row[1]).longValue();
            viewsById.put(id, views);
        }
        return viewsById.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(COMPARABLE_MOVIES_LIMIT)
                .map(e -> {
                    ManagedEntity m = byId.get(e.getKey());
                    Double budget = RecommendedActionCandidateServiceImpl.hasRealBudget(m.getBudget())
                            ? m.getBudget() : null;
                    return new SituationRecommendationResponse.ComparableMovieView(m.getName(), e.getValue(), budget);
                })
                .toList();
    }

    private void applyLlmRecommendation(ManagedEntity entity, SituationRecommendationResponse r) {
        String prompt = buildPrompt(entity, r);
        String reply;
        try {
            reply = llmService.generateReply(prompt);
        } catch (Exception e) {
            log.warn("Situation recommendation LLM call failed for entity {} — falling back to generic guidance",
                    entity.getId(), e);
            applyFallbackRecommendation(r);
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(reply);
        } catch (Exception e) {
            log.warn("Situation recommendation LLM response could not be parsed as JSON for entity {} — falling " +
                    "back to generic guidance", entity.getId(), e);
            applyFallbackRecommendation(r);
            return;
        }

        String recommendedAction = textOrNull(node, "recommendedAction");
        if (recommendedAction == null || recommendedAction.isBlank()) {
            log.warn("Situation recommendation LLM response had no usable recommendedAction for entity {} — " +
                    "falling back to generic guidance", entity.getId());
            applyFallbackRecommendation(r);
            return;
        }
        r.setRecommendedAction(recommendedAction);
        r.setReferencedMovie(textOrNull(node, "referencedMovie"));
        r.setWhatThatMovieDid(textOrNull(node, "whatThatMovieDid"));
        r.setRationale(textOrNull(node, "rationale"));
    }

    // Used only when the LLM call fails, the reply can't be parsed, or it has no usable
    // recommendedAction - the panel should still show something grounded in this movie's own real data
    // rather than go empty, same fallback-on-LLM-failure principle as
    // RecommendedActionsService#fallbackActions. Unlike that fallback, there is no server-computed
    // historical precedent to fall back on here (see this class's own javadoc), so referencedMovie/
    // whatThatMovieDid/rationale are left null rather than guessed.
    private static void applyFallbackRecommendation(SituationRecommendationResponse r) {
        if (!r.isHasSocialActivity()) {
            r.setRecommendedAction("No social-media activity has been tracked for this movie in the last 7 days. " +
                    "Prioritize awareness-building outreach (teaser/trailer pushes, influencer seeding) to generate " +
                    "initial buzz.");
        } else if (r.isNegativeBurstDetected()) {
            r.setRecommendedAction("Negative posts in the last 24 hours are running well above this movie's " +
                    "recent daily average. Review the negativity themes and key negative excerpts above and " +
                    "prepare a timely response addressing them directly.");
        } else {
            r.setRecommendedAction("Continue monitoring sentiment; no unusual negativity burst detected in the " +
                    "last 24 hours relative to the last 7 days.");
        }
        r.setReferencedMovie(null);
        r.setWhatThatMovieDid(null);
        r.setRationale("Generated from this movie's own tracked data only; the LLM-backed historical-precedent " +
                "recommendation was unavailable for this request.");
    }

    private static String textOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String text = node.get(field).asText().trim();
        return text.isEmpty() ? null : text;
    }

    private String buildPrompt(ManagedEntity entity, SituationRecommendationResponse r) {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode movie = root.putObject("movie");
        movie.put("name", entity.getName());
        putIfPresent(movie, "genre", entity.getGenre());
        putIfPresent(movie, "language", entity.getLanguage());
        putIfPresent(movie, "industry", entity.getIndustry());
        if (r.getDaysToRelease() != null) {
            movie.put("daysToRelease", r.getDaysToRelease());
            movie.put("releaseStatus", releaseStatusText(r.getDaysToRelease()));
        }

        ObjectNode activity = root.putObject("socialActivity");
        activity.put("hasSocialActivity", r.isHasSocialActivity());
        activity.put("postsLast7Days", r.getPostsLast7Days());
        activity.put("positiveCountLast7Days", r.getPositiveCountLast7Days());
        activity.put("negativeCountLast7Days", r.getNegativeCountLast7Days());
        activity.put("neutralCountLast7Days", r.getNeutralCountLast7Days());
        activity.put("postsLast24Hours", r.getPostsLast24Hours());
        activity.put("positiveCountLast24Hours", r.getPositiveCountLast24Hours());
        activity.put("negativeCountLast24Hours", r.getNegativeCountLast24Hours());
        root.put("negativeBurstDetected", r.isNegativeBurstDetected());

        ArrayNode themes = root.putArray("negativityThemes");
        r.getNegativityThemes().forEach(themes::add);
        ArrayNode negPoints = root.putArray("keyNegativePoints");
        r.getKeyNegativePoints().forEach(negPoints::add);
        ArrayNode posPoints = root.putArray("keyPositivePoints");
        r.getKeyPositivePoints().forEach(posPoints::add);

        root.put("ownTotalViews", r.getOwnTotalViews());
        root.put("comparableMoviesBudgetScoped", r.isComparableMoviesBudgetScoped());
        ArrayNode comparable = root.putArray("comparableMovies");
        for (SituationRecommendationResponse.ComparableMovieView c : r.getComparableMovies()) {
            ObjectNode n = comparable.addObject();
            n.put("name", c.getName());
            n.put("totalViews", c.getTotalViews());
            if (c.getBudget() != null) {
                n.put("budget", c.getBudget());
            }
        }
        if (r.getComparableAvgRevenue() != null) {
            root.put("comparableAvgRevenue", r.getComparableAvgRevenue());
        }
        if (r.getComparableSampleCount() != null) {
            root.put("comparableSampleCount", r.getComparableSampleCount());
        }

        return llmPrompt.replace(SITUATION_DATA_PLACEHOLDER, root.toString());
    }

    // Spells out movie.daysToRelease's signed integer as an unambiguous sentence for the LLM prompt -
    // a model asked to do the pre/post-release arithmetic itself off a bare signed number (see
    // daysToRelease's own javadoc for the sign convention) has been observed to get it backwards, e.g.
    // phrasing a movie released 29 days ago as "29 days before release". movie.daysToRelease is still
    // sent alongside this for any downstream consumer that wants the raw number, but the prompt's own
    // Instruction 6 treats this field as the authoritative one for deciding pre- vs post-release framing.
    private static String releaseStatusText(int daysToRelease) {
        if (daysToRelease > 0) {
            return "released " + daysToRelease + " day" + (daysToRelease == 1 ? "" : "s") + " ago";
        }
        if (daysToRelease < 0) {
            int daysOut = -daysToRelease;
            return "releasing in " + daysOut + " day" + (daysOut == 1 ? "" : "s");
        }
        return "releasing today";
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
