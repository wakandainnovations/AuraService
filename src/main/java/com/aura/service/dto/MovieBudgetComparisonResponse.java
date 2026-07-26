package com.aura.service.dto;

import java.util.List;

/**
 * Compares the target movie's audience against other {@code MOVIE} entities budgeted within
 * {@code +-50%} of it (any language), to show comparatively how well the target is performing.
 * {@code comparableMovies} excludes the target itself and is sorted by
 * {@link ComparableMovieStats#uniqueAudienceCount()} descending.
 *
 * @param targetAudiencePercentileInRange {@code targetUniqueAudienceCount} as a percentage of the
 *                                        highest {@code uniqueAudienceCount} across the target
 *                                        movie and all comparable movies in the budget range (100
 *                                        = the range's top audience); {@code null} when every
 *                                        movie in the range has zero qualifying audience
 */
public record MovieBudgetComparisonResponse(
        String targetMovieName,
        String targetLanguage,
        Double targetBudget,
        long targetUniqueAudienceCount,
        long targetTotalPosts,
        Double targetAudiencePercentileInRange,
        double budgetRangeMinUsd,
        double budgetRangeMaxUsd,
        List<ComparableMovieStats> comparableMovies) {
}
