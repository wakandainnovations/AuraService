package com.aura.service.dto;

/**
 * One industry or language cohort's aggregate engagement across every tracked movie in it.
 * {@code totalPosts} sums each movie's own post-attribution count - a mention linked to two movies
 * in the same cohort counts toward both, mirroring {@code MentionRepository#countAudienceAndPostsPerEntity}
 * - while {@code uniqueAuthors} counts a poster once even if they posted about several of the
 * cohort's movies, mirroring the audience-size convention in {@link LanguageAudienceResponse}.
 */
public record CohortEngagementStats(
        String cohort,
        int movieCount,
        long totalPosts,
        long uniqueAuthors,
        long totalLikes,
        long totalComments,
        long totalEngagement,
        double avgEngagementPerPost,
        double avgSentimentScore,
        double positiveSentimentRatio) {
}
