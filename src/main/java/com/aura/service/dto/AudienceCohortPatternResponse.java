package com.aura.service.dto;

import java.util.List;

/**
 * Engagement compared across industry or language cohorts (every tracked {@code MOVIE} entity
 * grouped by {@link com.aura.service.enums.CohortGroupBy}), so marketing can see which audience
 * segments respond most before allocating spend across industries/languages. Sorted by
 * {@code totalEngagement} descending.
 */
public record AudienceCohortPatternResponse(
        String groupBy,
        int cohortCount,
        List<CohortEngagementStats> cohorts) {
}
