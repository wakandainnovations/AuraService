package com.aura.service.service;

import com.aura.service.dto.ComparableMovieStats;
import com.aura.service.dto.LanguageAudienceResponse;
import com.aura.service.dto.MovieAudienceDetailResponse;
import com.aura.service.dto.MovieBudgetComparisonResponse;
import com.aura.service.dto.UserEngagementStats;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MovieAudienceServiceImpl}, with the repositories and
 * {@link EntityAccessService} mocked as interfaces (not concrete classes, per this codebase's
 * Mockito-on-Java-25 constraint).
 */
class MovieAudienceServiceImplTest {

    private static final Long OWNER_ID = 1L;

    private ManagedEntityRepository entityRepository;
    private MentionRepository mentionRepository;
    private EntityAccessService entityAccessService;
    private MovieAudienceServiceImpl service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        mentionRepository = mock(MentionRepository.class);
        entityAccessService = mock(EntityAccessService.class);
        service = new MovieAudienceServiceImpl(entityRepository, mentionRepository, entityAccessService);

        // Default: caller is a plain (non-admin) user scoped to OWNER_ID.
        when(entityAccessService.resolveOwnerScope(any())).thenReturn(OWNER_ID);
    }

    private ManagedEntity movie(Long id, String name, String language, Double budget) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName(name);
        e.setType("MOVIE");
        e.setLanguage(language);
        e.setBudget(budget);
        return e;
    }

    // ------------------------------------------------------------------
    // getLanguageAudience
    // ------------------------------------------------------------------

    @Test
    void getLanguageAudience_returnsMovieCountAndUniqueAudienceAcrossAllMoviesInLanguage() {
        ManagedEntity kgf = movie(10L, "KGF", "Kannada", 1_000_000.0);
        ManagedEntity kantara = movie(11L, "Kantara", "Kannada", 500_000.0);
        when(entityRepository.findByTypeAndLanguageIgnoreCaseAndOwnerId("MOVIE", "Kannada", OWNER_ID))
                .thenReturn(List.of(kgf, kantara));
        when(mentionRepository.countDistinctAuthorsByEntityIdsNonZeroSentiment(List.of(10L, 11L)))
                .thenReturn(42L);

        LanguageAudienceResponse response = service.getLanguageAudience("Kannada", null);

        assertThat(response.language()).isEqualTo("Kannada");
        assertThat(response.movieCount()).isEqualTo(2);
        assertThat(response.uniqueAudienceCount()).isEqualTo(42L);
        assertThat(response.movieNames()).containsExactlyInAnyOrder("KGF", "Kantara");
    }

    @Test
    void getLanguageAudience_adminUnscoped_queriesAcrossAllOwners() {
        when(entityAccessService.resolveOwnerScope(null)).thenReturn(null);
        ManagedEntity kgf = movie(10L, "KGF", "Kannada", 1_000_000.0);
        when(entityRepository.findByTypeAndLanguageIgnoreCase("MOVIE", "Kannada")).thenReturn(List.of(kgf));
        when(mentionRepository.countDistinctAuthorsByEntityIdsNonZeroSentiment(List.of(10L))).thenReturn(7L);

        LanguageAudienceResponse response = service.getLanguageAudience("Kannada", null);

        assertThat(response.uniqueAudienceCount()).isEqualTo(7L);
    }

    @Test
    void getLanguageAudience_throwsNotFoundWhenNoMoviesInLanguage() {
        when(entityRepository.findByTypeAndLanguageIgnoreCaseAndOwnerId("MOVIE", "Bhojpuri", OWNER_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getLanguageAudience("Bhojpuri", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // getMovieAudienceDetail
    // ------------------------------------------------------------------

    @Test
    void getMovieAudienceDetail_computesEngagementRatioAndPositiveRatioPerUser() {
        ManagedEntity kgf = movie(10L, "KGF", "Kannada", 1_000_000.0);
        when(entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
                "MOVIE", "KGF", "Kannada", OWNER_ID)).thenReturn(List.of(kgf));
        when(mentionRepository.findAuthorEngagementStats(List.of(10L))).thenReturn(Arrays.<Object[]>asList(
                new Object[]{"alice", 6L, 4.5, 5L},
                new Object[]{"bob", 4L, 2.0, 1L}
        ));

        MovieAudienceDetailResponse response =
                service.getMovieAudienceDetail("Kannada", "KGF", null, null);

        assertThat(response.movieName()).isEqualTo("KGF");
        assertThat(response.language()).isEqualTo("Kannada");
        assertThat(response.uniqueAudienceCount()).isEqualTo(2L);
        assertThat(response.totalPosts()).isEqualTo(10L);

        // Sorted by postCount descending: alice (6 posts) before bob (4 posts).
        List<UserEngagementStats> users = response.users();
        assertThat(users).hasSize(2);

        UserEngagementStats alice = users.get(0);
        assertThat(alice.author()).isEqualTo("alice");
        assertThat(alice.postCount()).isEqualTo(6L);
        assertThat(alice.engagementRatio()).isCloseTo(0.6, within(0.0001));
        assertThat(alice.averageSentimentScore()).isCloseTo(4.5, within(0.0001));
        assertThat(alice.positiveRatio()).isCloseTo(5.0 / 6.0, within(0.0001));

        UserEngagementStats bob = users.get(1);
        assertThat(bob.author()).isEqualTo("bob");
        assertThat(bob.postCount()).isEqualTo(4L);
        assertThat(bob.engagementRatio()).isCloseTo(0.4, within(0.0001));
        assertThat(bob.positiveRatio()).isCloseTo(0.25, within(0.0001));
    }

    @Test
    void getMovieAudienceDetail_treatsNullAverageAndPositiveCountAsZero() {
        ManagedEntity kgf = movie(10L, "KGF", "Kannada", 1_000_000.0);
        when(entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
                "MOVIE", "KGF", "Kannada", OWNER_ID)).thenReturn(List.of(kgf));
        when(mentionRepository.findAuthorEngagementStats(List.of(10L))).thenReturn(Arrays.<Object[]>asList(
                new Object[]{"carol", 3L, null, null}
        ));

        MovieAudienceDetailResponse response =
                service.getMovieAudienceDetail("Kannada", "KGF", null, null);

        UserEngagementStats carol = response.users().get(0);
        assertThat(carol.averageSentimentScore()).isEqualTo(0.0);
        assertThat(carol.positiveRatio()).isEqualTo(0.0);
    }

    @Test
    void getMovieAudienceDetail_limitClampsToRequestedTopUsers() {
        ManagedEntity kgf = movie(10L, "KGF", "Kannada", 1_000_000.0);
        when(entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
                "MOVIE", "KGF", "Kannada", OWNER_ID)).thenReturn(List.of(kgf));
        when(mentionRepository.findAuthorEngagementStats(List.of(10L))).thenReturn(Arrays.<Object[]>asList(
                new Object[]{"alice", 6L, 4.5, 5L},
                new Object[]{"bob", 4L, 2.0, 1L}
        ));

        MovieAudienceDetailResponse response =
                service.getMovieAudienceDetail("Kannada", "KGF", null, 1);

        // uniqueAudienceCount still reflects the true total; only the returned list is capped.
        assertThat(response.uniqueAudienceCount()).isEqualTo(2L);
        assertThat(response.users()).hasSize(1);
        assertThat(response.users().get(0).author()).isEqualTo("alice");
    }

    @Test
    void getMovieAudienceDetail_throwsNotFoundWhenNoMatchingMovie() {
        when(entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
                "MOVIE", "Unknown", "Kannada", OWNER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getMovieAudienceDetail("Kannada", "Unknown", null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // getBudgetComparison
    // ------------------------------------------------------------------

    @Test
    void getBudgetComparison_returnsPeersWithinHalfRangeAndAudiencePercentiles() {
        ManagedEntity target = movie(10L, "KGF", "Kannada", 1_000_000.0);
        when(entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
                "MOVIE", "KGF", "Kannada", OWNER_ID)).thenReturn(List.of(target));

        ManagedEntity peerA = movie(20L, "Vikrant Rona", "Kannada", 1_100_000.0);
        ManagedEntity peerB = movie(21L, "RRR", "Telugu", 900_000.0);
        when(entityRepository.findByTypeAndBudgetBetweenAndIdNotAndOwnerId(
                eq("MOVIE"), eq(500_000.0), eq(1_500_000.0), eq(10L), eq(OWNER_ID)))
                .thenReturn(List.of(peerA, peerB));

        when(mentionRepository.countAudienceAndPostsPerEntity(List.of(10L, 20L, 21L)))
                .thenReturn(Arrays.<Object[]>asList(
                        new Object[]{10L, 40L, 100L},
                        new Object[]{20L, 20L, 50L},
                        new Object[]{21L, 80L, 200L}
                ));

        MovieBudgetComparisonResponse response = service.getBudgetComparison("KGF", "Kannada", null);

        assertThat(response.targetMovieName()).isEqualTo("KGF");
        assertThat(response.targetLanguage()).isEqualTo("Kannada");
        assertThat(response.targetBudget()).isEqualTo(1_000_000.0);
        assertThat(response.targetUniqueAudienceCount()).isEqualTo(40L);
        assertThat(response.targetTotalPosts()).isEqualTo(100L);
        // Highest audience in range (target 40, RRR 80, Vikrant 20) is RRR's 80, so target = 40/80*100.
        assertThat(response.targetAudiencePercentileInRange()).isCloseTo(50.0, within(0.0001));
        assertThat(response.budgetRangeMinUsd()).isCloseTo(500_000.0, within(0.01));
        assertThat(response.budgetRangeMaxUsd()).isCloseTo(1_500_000.0, within(0.01));

        // Sorted by uniqueAudienceCount descending: RRR (80) before Vikrant Rona (20).
        List<ComparableMovieStats> comparable = response.comparableMovies();
        assertThat(comparable).hasSize(2);

        ComparableMovieStats rrr = comparable.get(0);
        assertThat(rrr.movieName()).isEqualTo("RRR");
        assertThat(rrr.language()).isEqualTo("Telugu");
        assertThat(rrr.uniqueAudienceCount()).isEqualTo(80L);
        assertThat(rrr.audiencePercentileInRange()).isCloseTo(100.0, within(0.0001));

        ComparableMovieStats vikrant = comparable.get(1);
        assertThat(vikrant.uniqueAudienceCount()).isEqualTo(20L);
        assertThat(vikrant.audiencePercentileInRange()).isCloseTo(25.0, within(0.0001));
    }

    @Test
    void getBudgetComparison_audiencePercentileIsNullWhenEveryMovieInRangeHasZeroAudience() {
        ManagedEntity target = movie(10L, "KGF", "Kannada", 1_000_000.0);
        when(entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
                "MOVIE", "KGF", "Kannada", OWNER_ID)).thenReturn(List.of(target));

        ManagedEntity peer = movie(20L, "Vikrant Rona", "Kannada", 1_100_000.0);
        when(entityRepository.findByTypeAndBudgetBetweenAndIdNotAndOwnerId(
                eq("MOVIE"), eq(500_000.0), eq(1_500_000.0), eq(10L), eq(OWNER_ID)))
                .thenReturn(List.of(peer));

        when(mentionRepository.countAudienceAndPostsPerEntity(List.of(10L, 20L)))
                .thenReturn(List.of());
        // no rows -> target and peer both have zero qualifying audience

        MovieBudgetComparisonResponse response = service.getBudgetComparison("KGF", "Kannada", null);

        assertThat(response.targetUniqueAudienceCount()).isEqualTo(0L);
        assertThat(response.targetAudiencePercentileInRange()).isNull();
        assertThat(response.comparableMovies().get(0).audiencePercentileInRange()).isNull();
    }

    @Test
    void getBudgetComparison_throwsIllegalArgumentWhenNameAmbiguousWithoutLanguage() {
        ManagedEntity kannadaMovie = movie(10L, "Vikram", "Kannada", 1_000_000.0);
        ManagedEntity tamilMovie = movie(11L, "Vikram", "Tamil", 900_000.0);
        when(entityRepository.findByTypeAndNameIgnoreCaseAndOwnerId("MOVIE", "Vikram", OWNER_ID))
                .thenReturn(List.of(kannadaMovie, tamilMovie));

        assertThatThrownBy(() -> service.getBudgetComparison("Vikram", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getBudgetComparison_throwsIllegalArgumentWhenTargetHasNoBudget() {
        ManagedEntity target = movie(10L, "KGF", "Kannada", null);
        when(entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
                "MOVIE", "KGF", "Kannada", OWNER_ID)).thenReturn(List.of(target));

        assertThatThrownBy(() -> service.getBudgetComparison("KGF", "Kannada", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getBudgetComparison_throwsNotFoundWhenNoMatchingMovie() {
        when(entityRepository.findByTypeAndNameIgnoreCaseAndOwnerId("MOVIE", "Unknown", OWNER_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getBudgetComparison("Unknown", null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
