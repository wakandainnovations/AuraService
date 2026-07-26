package com.aura.service.dto;

/**
 * A single user's engagement with one movie, over mentions with a non-zero sentiment score.
 *
 * @param postCount              how many qualifying posts this user made about the movie
 * @param engagementRatio        {@code postCount / totalPosts} for the movie - this user's share of
 *                                the whole qualifying conversation
 * @param averageSentimentScore  average of this user's own {@code sentimentScore} values
 * @param positiveRatio          fraction of this user's posts about the movie rated POSITIVE
 */
public record UserEngagementStats(
        String author,
        long postCount,
        double engagementRatio,
        double averageSentimentScore,
        double positiveRatio) {
}
