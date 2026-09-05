package com.aura.service.service;

import com.aura.service.dto.SituationRecommendationResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.SituationRecommendationCache;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.SituationRecommendationCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link SituationRecommendationService}: server-computed counts/window math (including the
 * negative-burst threshold), the cache hit/miss/TTL/refresh plumbing, and the LLM parse/fallback path -
 * mirroring {@link RecommendedActionsServiceTest}'s conventions for this codebase (repositories mocked
 * as interfaces; no {@code EntityManager} involved so no Java 25 / Mockito concrete-class constraint
 * applies here).
 */
class SituationRecommendationServiceTest {

    private static final Long ENTITY_ID = 42L;
    private static final String PROMPT_TEMPLATE = "[Situation Data]";

    private ManagedEntityRepository managedEntityRepository;
    private MentionRepository mentionRepository;
    private MoviesDataCollectionQueryService moviesDataQueryService;
    private SituationRecommendationCacheRepository cacheRepository;
    private LLMService llmService;
    private Clock clock;
    private SituationRecommendationService service;

    private Instant now;
    private Instant sevenDaysAgo;
    private Instant oneDayAgo;

    @BeforeEach
    void setUp() {
        managedEntityRepository = mock(ManagedEntityRepository.class);
        mentionRepository = mock(MentionRepository.class);
        moviesDataQueryService = mock(MoviesDataCollectionQueryService.class);
        cacheRepository = mock(SituationRecommendationCacheRepository.class);
        llmService = mock(LLMService.class);
        clock = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC);
        now = clock.instant();
        sevenDaysAgo = now.minus(Duration.ofDays(7));
        oneDayAgo = now.minus(Duration.ofHours(24));

        service = new SituationRecommendationService(
                managedEntityRepository, mentionRepository, moviesDataQueryService, cacheRepository, llmService, clock);
        ReflectionTestUtils.setField(service, "llmPrompt", PROMPT_TEMPLATE);

        // No comparable-movie/comps data in the default fixture - individual tests override this where
        // it matters to the assertion.
        when(moviesDataQueryService.findGenreLanguageBudgetComps(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(List.of());
    }

    private ManagedEntity movie(Double budget, String genre, String language) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        entity.setName("Toxic");
        entity.setType("MOVIE");
        entity.setGenre(genre);
        entity.setLanguage(language);
        entity.setIndustry("Sandalwood");
        entity.setBudget(budget);
        return entity;
    }

    private void stubCounts(long posts7d, long pos7d, long neg7d, long neu7d,
                             long posts24h, long pos24h, long neg24h) {
        // Raw post VOLUME now comes off the four platform tables (countRawPostsForEntitySince), not
        // mentions - see SituationRecommendationService's own javadoc for why. Sentiment-specific counts
        // stay on mentions.
        when(mentionRepository.countRawPostsForEntitySince(ENTITY_ID, sevenDaysAgo)).thenReturn(posts7d);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(ENTITY_ID, Sentiment.POSITIVE, sevenDaysAgo))
                .thenReturn(pos7d);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(ENTITY_ID, Sentiment.NEGATIVE, sevenDaysAgo))
                .thenReturn(neg7d);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(ENTITY_ID, Sentiment.NEUTRAL, sevenDaysAgo))
                .thenReturn(neu7d);
        when(mentionRepository.countRawPostsForEntitySince(ENTITY_ID, oneDayAgo)).thenReturn(posts24h);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(ENTITY_ID, Sentiment.POSITIVE, oneDayAgo))
                .thenReturn(pos24h);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateAfter(ENTITY_ID, Sentiment.NEGATIVE, oneDayAgo))
                .thenReturn(neg24h);
    }

    private void stubEmptyThemesAndExcerpts() {
        when(mentionRepository.findReviewAspectCountsForEntityAndSentimentSince(eq(ENTITY_ID), any(), any()))
                .thenReturn(List.of());
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentAndPostDateAfter(
                eq(ENTITY_ID), any(), any(), any(Pageable.class))).thenReturn(List.<Mention>of());
    }

    @Test
    void burstDetectedWhenTodaysNegativesFarExceedPriorSixDayAverage() {
        ManagedEntity entity = movie(null, null, "Kannada");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        // 7-day negative total 11, of which 10 landed in the last 24h -> prior 6 days averaged
        // (11-10)/6 ~= 0.17/day; today's 10 is far more than 2x that and clears the absolute floor.
        stubCounts(20, 5, 11, 4, 12, 1, 10);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(500L);
        when(llmService.generateReply(anyString())).thenThrow(new RuntimeException("llm down"));

        SituationRecommendationResponse response = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(response.isNegativeBurstDetected()).isTrue();
        assertThat(response.isHasSocialActivity()).isTrue();
        assertThat(response.getRecommendedAction()).contains("above this movie's");
    }

    @Test
    void noBurstWhenNegativesAreSmallInAbsoluteTerms() {
        ManagedEntity entity = movie(null, null, "Kannada");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        // Today's negative count (2) is below BURST_MIN_ABSOLUTE_NEGATIVE_POSTS even though it's a
        // large multiple of a near-zero prior average - the absolute floor should suppress the flag.
        stubCounts(5, 2, 3, 0, 3, 1, 2);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(0L);
        when(llmService.generateReply(anyString())).thenThrow(new RuntimeException("llm down"));

        SituationRecommendationResponse response = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(response.isNegativeBurstDetected()).isFalse();
    }

    @Test
    void noSocialActivityStillProducesASituationWhenLlmUnavailable() {
        ManagedEntity entity = movie(null, "Action,Drama", "Kannada");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        stubCounts(0, 0, 0, 0, 0, 0, 0);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(0L);
        when(managedEntityRepository.findByTypeAndLanguageIgnoreCase("MOVIE", "Kannada")).thenReturn(List.of());
        when(llmService.generateReply(anyString())).thenThrow(new RuntimeException("llm down"));

        SituationRecommendationResponse response = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(response.isHasSocialActivity()).isFalse();
        assertThat(response.getRecommendedAction()).contains("No social-media activity");
        assertThat(response.getReferencedMovie()).isNull();
    }

    @Test
    void parsesLlmRecommendationFields() {
        ManagedEntity entity = movie(1_000_000.0, "Action", "Kannada");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        stubCounts(10, 6, 4, 0, 3, 2, 1);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(1000L);
        when(managedEntityRepository.findByTypeAndBudgetBetweenAndIdNot(eq("MOVIE"), anyDouble(), anyDouble(), eq(ENTITY_ID)))
                .thenReturn(List.of());
        when(llmService.generateReply(anyString())).thenReturn("""
                {
                  "recommendedAction": "Address the story criticism directly with a behind-the-scenes video.",
                  "referencedMovie": "Example Movie",
                  "whatThatMovieDid": "Its team released a director's note addressing the criticism.",
                  "rationale": "This movie's negativity is concentrated in the same theme."
                }
                """);

        SituationRecommendationResponse response = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(response.getRecommendedAction()).isEqualTo(
                "Address the story criticism directly with a behind-the-scenes video.");
        assertThat(response.getReferencedMovie()).isEqualTo("Example Movie");
        assertThat(response.getWhatThatMovieDid()).isEqualTo("Its team released a director's note addressing the criticism.");
        verify(cacheRepository).save(any(SituationRecommendationCache.class));
    }

    @Test
    void cachedResponseWithinTtlIsReusedWithoutRecomputation() {
        ManagedEntity entity = movie(null, null, "Kannada");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubCounts(10, 6, 4, 0, 3, 2, 1);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(1000L);
        when(llmService.generateReply(anyString())).thenReturn("""
                {"recommendedAction": "Do X.", "referencedMovie": "Movie A", \
                "whatThatMovieDid": "Did Y.", "rationale": "Because Z."}
                """);

        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        SituationRecommendationResponse first = service.getSituationRecommendation(ENTITY_ID, false);

        ArgumentCaptor<SituationRecommendationCache> savedCaptor = ArgumentCaptor.forClass(SituationRecommendationCache.class);
        verify(cacheRepository).save(savedCaptor.capture());
        SituationRecommendationCache saved = savedCaptor.getValue();
        assertThat(saved.getGeneratedAt()).isEqualTo(now);

        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(saved));
        SituationRecommendationResponse second = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(second.getRecommendedAction()).isEqualTo(first.getRecommendedAction());
        verify(llmService, times(1)).generateReply(anyString());
        verify(managedEntityRepository, times(1)).findById(ENTITY_ID);
    }

    @Test
    void refreshTrueForcesRegenerationEvenWhenCacheIsFresh() {
        ManagedEntity entity = movie(null, null, "Kannada");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubCounts(10, 6, 4, 0, 3, 2, 1);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(1000L);
        when(llmService.generateReply(anyString())).thenReturn("""
                {"recommendedAction": "Do X.", "referencedMovie": "Movie A", \
                "whatThatMovieDid": "Did Y.", "rationale": "Because Z."}
                """);

        SituationRecommendationCache fresh = new SituationRecommendationCache();
        fresh.setEntityId(ENTITY_ID);
        fresh.setResponseJson("{}");
        fresh.setGeneratedAt(now);
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.of(fresh));

        service.getSituationRecommendation(ENTITY_ID, true);

        verify(llmService, times(1)).generateReply(anyString());
        verify(managedEntityRepository, times(1)).findById(ENTITY_ID);
    }

    @Test
    void unparseableLlmReplyFallsBackToGenericGuidance() {
        ManagedEntity entity = movie(null, null, "Kannada");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        stubCounts(10, 6, 4, 0, 3, 2, 1);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(1000L);
        when(llmService.generateReply(anyString())).thenReturn("not json at all");

        SituationRecommendationResponse response = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(response.getRecommendedAction()).isNotBlank();
        assertThat(response.getReferencedMovie()).isNull();
        assertThat(response.getRationale()).contains("LLM-backed historical-precedent recommendation was unavailable");
    }

    // ==================== Raw-table post volume, not mentions (mentions can lag the raw tables) ====================

    @Test
    void postVolumeComesFromRawPlatformTables_notFromMentionsPostDateCount() {
        ManagedEntity entity = movie(null, null, "Kannada");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        stubCounts(10, 6, 4, 0, 3, 2, 1);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(0L);
        when(llmService.generateReply(anyString())).thenThrow(new RuntimeException("llm down"));

        SituationRecommendationResponse response = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(response.getPostsLast7Days()).isEqualTo(10);
        assertThat(response.getPostsLast24Hours()).isEqualTo(3);
        verify(mentionRepository).countRawPostsForEntitySince(ENTITY_ID, sevenDaysAgo);
        verify(mentionRepository).countRawPostsForEntitySince(ENTITY_ID, oneDayAgo);
        verify(mentionRepository, never()).countByManagedEntityIdAndPostDateAfter(any(), any());
    }

    // ==================== daysToRelease ====================

    @Test
    void daysToReleaseIsComputedAndSentToLlm() {
        ManagedEntity entity = movie(null, null, "Kannada");
        // "now" is fixed to 2026-08-10T10:00:00Z; a release date 5 days later should read as -5
        // (negative = before release, same sign convention as RecommendedActionsService).
        entity.setReleaseDate(java.time.LocalDate.of(2026, 8, 15));
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        stubCounts(10, 6, 4, 0, 3, 2, 1);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(0L);
        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(llmService.generateReply(promptCaptor.capture())).thenReturn("""
                {"recommendedAction": "Do X.", "referencedMovie": "Movie A", \
                "whatThatMovieDid": "Did Y.", "rationale": "Because Z."}
                """);

        SituationRecommendationResponse response = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(response.getDaysToRelease()).isEqualTo(-5);
        assertThat(promptCaptor.getValue()).contains("\"daysToRelease\":-5");
        assertThat(promptCaptor.getValue()).contains("\"releaseStatus\":\"releasing in 5 days\"");
    }

    @Test
    void releaseStatusIsSpelledOutForAnAlreadyReleasedMovie() {
        ManagedEntity entity = movie(null, null, "Kannada");
        // "now" is fixed to 2026-08-10T10:00:00Z; a release date 5 days earlier should read as +5
        // (positive = already released) and releaseStatus should say so in plain, unambiguous words
        // rather than leaving the LLM to infer it from the signed daysToRelease number.
        entity.setReleaseDate(java.time.LocalDate.of(2026, 8, 5));
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        stubCounts(10, 6, 4, 0, 3, 2, 1);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(0L);
        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(llmService.generateReply(promptCaptor.capture())).thenReturn("""
                {"recommendedAction": "Do X.", "referencedMovie": "Movie A", \
                "whatThatMovieDid": "Did Y.", "rationale": "Because Z."}
                """);

        SituationRecommendationResponse response = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(response.getDaysToRelease()).isEqualTo(5);
        assertThat(promptCaptor.getValue()).contains("\"releaseStatus\":\"released 5 days ago\"");
    }

    @Test
    void daysToReleaseIsNullWhenEntityHasNoReleaseDate() {
        ManagedEntity entity = movie(null, null, "Kannada");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(cacheRepository.findByEntityId(ENTITY_ID)).thenReturn(Optional.empty());
        stubCounts(10, 6, 4, 0, 3, 2, 1);
        stubEmptyThemesAndExcerpts();
        when(mentionRepository.findTotalViewsForEntity(ENTITY_ID)).thenReturn(0L);
        when(llmService.generateReply(anyString())).thenThrow(new RuntimeException("llm down"));

        SituationRecommendationResponse response = service.getSituationRecommendation(ENTITY_ID, false);

        assertThat(response.getDaysToRelease()).isNull();
    }
}
