package com.aura.service.service;

import com.aura.service.dto.LanguageAudienceResponse;
import com.aura.service.dto.MovieAudienceDetailResponse;
import com.aura.service.dto.MovieBudgetComparisonResponse;

/**
 * Audience-size analytics over {@code MOVIE} entities and their {@link com.aura.service.entity.Mention}s.
 * Every query counts only mentions with a non-zero {@code sentimentScore} (NULL and 0 are both
 * excluded) and is scoped to the movies the caller may see - see {@link EntityAccessService}.
 */
public interface MovieAudienceService {

    /**
     * Total unique users who posted about any {@code MOVIE} entity in {@code language}, plus how
     * many movies in that language were considered.
     *
     * @throws com.aura.service.exception.ResourceNotFoundException if no movie in that language is
     *         visible to the caller
     */
    LanguageAudienceResponse getLanguageAudience(String language, Long requestedOwnerId);

    /**
     * Unique users who posted about {@code movieName} in {@code language}, with per-user post
     * count, engagement ratio, average sentiment score, and positive-sentiment ratio.
     *
     * @param limit max users to return, sorted by post count descending; null uses the default
     * @throws com.aura.service.exception.ResourceNotFoundException if no matching movie is visible
     *         to the caller
     */
    MovieAudienceDetailResponse getMovieAudienceDetail(
            String language, String movieName, Long requestedOwnerId, Integer limit);

    /**
     * Movies budgeted within +-50% of {@code movieName}'s budget, each with its own audience size,
     * so the target movie's performance can be read against comparable-budget peers.
     *
     * @param language optional; disambiguates when the caller has more than one movie with the same
     *                 name (e.g. a remake tracked in two languages)
     * @throws com.aura.service.exception.ResourceNotFoundException if no matching movie is visible
     *         to the caller
     * @throws IllegalArgumentException if the name (± language) matches more than one movie, or the
     *         matched movie has no budget recorded
     */
    MovieBudgetComparisonResponse getBudgetComparison(String movieName, String language, Long requestedOwnerId);
}
