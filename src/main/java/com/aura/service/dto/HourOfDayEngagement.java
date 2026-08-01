package com.aura.service.dto;

/** Aggregate engagement for one UTC hour-of-day (0-23) bucket, summed across every matching day. */
public record HourOfDayEngagement(
        int hourUtc,
        long postCount,
        long uniqueAuthors,
        long totalLikes,
        long totalComments,
        long totalEngagement,
        double avgEngagementPerPost) {
}
