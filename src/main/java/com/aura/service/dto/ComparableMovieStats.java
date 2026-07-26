package com.aura.service.dto;

/**
 * One movie within {@code +-50%} of the target movie's budget, with its own audience metadata so
 * it can be compared side by side with the target - see {@code MovieBudgetComparisonResponse}.
 *
 * @param audiencePercentileInRange this movie's {@code uniqueAudienceCount} as a percentage of the
 *                                  highest {@code uniqueAudienceCount} across the target movie and
 *                                  all comparable movies in the budget range (100 = the range's top
 *                                  audience); {@code null} when every movie in the range has zero
 *                                  qualifying audience (the ratio would be meaningless)
 */
public record ComparableMovieStats(
        String movieName,
        String language,
        Double budget,
        long uniqueAudienceCount,
        long totalPosts,
        Double audiencePercentileInRange) {
}
