package com.aura.service.dto;

import java.util.List;

/**
 * When a movie's (or a language's/industry's) audience is most active, so marketing can schedule
 * posts and campaigns for maximum reach. Aggregated from every {@code MOVIE} entity matching the
 * requested language/industry/movieName scope. Engagement totals only cover mentions whose postId
 * still resolves in its platform's ingestion table; posts that don't resolve still count toward
 * {@code postCount} but contribute zero engagement. All hour/day bucketing uses UTC - the same
 * convention as {@code MentionRepository}'s existing hour/day-of-week native queries.
 */
public record AudienceTimingPatternResponse(
        String scope,
        int movieCount,
        long totalPosts,
        long uniqueAuthors,
        long totalEngagement,
        List<HourOfDayEngagement> byHourOfDay,
        List<DayOfWeekEngagement> byDayOfWeek,
        List<RecommendedTimeSlot> topTimeSlots) {
}
