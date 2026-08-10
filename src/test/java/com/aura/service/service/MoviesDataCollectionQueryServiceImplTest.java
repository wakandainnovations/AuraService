package com.aura.service.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the genre-token-overlap matching logic in isolation, since
 * {@link MoviesDataCollectionQueryServiceImpl} itself is a thin {@code EntityManager} native-query
 * wrapper this project's test conventions don't stand up a Spring/JPA context for (see
 * {@link RecommendedActionCandidateServiceImplTest}'s javadoc on why {@code EntityManager} can't be
 * mocked under this project's Java 25 toolchain either). Regression coverage for the bug where
 * {@code genre = :genre} exact-string matching against {@code movies_data_collection} never matched
 * this platform's own comma-separated, differently-ordered/formatted {@code ManagedEntity.genre}
 * values (e.g. "Action,Adventure,Psychedelic" vs. the comps table's "Action, Romance, Comedy,
 * Thriller").
 */
class MoviesDataCollectionQueryServiceImplTest {

    @Test
    void tokenizeGenreSplitsTrimsAndLowercases() {
        assertThat(MoviesDataCollectionQueryServiceImpl.tokenizeGenre("Action,Adventure,Psychedelic"))
                .containsExactlyInAnyOrder("action", "adventure", "psychedelic");
    }

    @Test
    void tokenizeGenreHandlesCommaSpaceFormat() {
        assertThat(MoviesDataCollectionQueryServiceImpl.tokenizeGenre("Action, Romance, Comedy, Thriller"))
                .containsExactlyInAnyOrder("action", "romance", "comedy", "thriller");
    }

    @Test
    void tokenizeGenreOnNullOrBlankReturnsEmptySet() {
        assertThat(MoviesDataCollectionQueryServiceImpl.tokenizeGenre(null)).isEmpty();
        assertThat(MoviesDataCollectionQueryServiceImpl.tokenizeGenre("  ")).isEmpty();
    }

    @Test
    void genreOverlapsWhenDifferentFormatsShareAToken() {
        Set<String> entityTokens = MoviesDataCollectionQueryServiceImpl.tokenizeGenre("Action,Adventure,Psychedelic");
        assertThat(MoviesDataCollectionQueryServiceImpl.genreOverlaps(entityTokens, "Action, Romance, Comedy, Thriller"))
                .isTrue();
    }

    @Test
    void genreOverlapsFalseWhenNoSharedToken() {
        Set<String> entityTokens = MoviesDataCollectionQueryServiceImpl.tokenizeGenre("Drama,Social");
        assertThat(MoviesDataCollectionQueryServiceImpl.genreOverlaps(entityTokens, "Action, Romance, Comedy, Thriller"))
                .isFalse();
    }

    @Test
    void genreOverlapsFalseOnNullOrBlankCompsGenre() {
        Set<String> entityTokens = MoviesDataCollectionQueryServiceImpl.tokenizeGenre("Action");
        assertThat(MoviesDataCollectionQueryServiceImpl.genreOverlaps(entityTokens, null)).isFalse();
        assertThat(MoviesDataCollectionQueryServiceImpl.genreOverlaps(entityTokens, "")).isFalse();
    }

    @Test
    void genreOverlapsFalseWhenEntityHasNoTokens() {
        assertThat(MoviesDataCollectionQueryServiceImpl.genreOverlaps(Set.of(), "Action, Drama")).isFalse();
    }
}
