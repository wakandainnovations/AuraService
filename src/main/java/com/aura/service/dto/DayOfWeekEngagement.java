package com.aura.service.dto;

import java.time.DayOfWeek;

/** Aggregate engagement for one day-of-week bucket (UTC), summed across every matching hour. */
public record DayOfWeekEngagement(
        DayOfWeek dayOfWeek,
        long postCount,
        long uniqueAuthors,
        long totalLikes,
        long totalComments,
        long totalEngagement,
        double avgEngagementPerPost) {
}
