package com.aura.service.dto;

import java.time.DayOfWeek;

/**
 * A specific (day-of-week, hour) combination ranked by total engagement - the concrete "post here"
 * recommendation the timing pattern is built to surface.
 */
public record RecommendedTimeSlot(
        DayOfWeek dayOfWeek,
        int hourUtc,
        long postCount,
        long totalEngagement,
        double avgEngagementPerPost) {
}
