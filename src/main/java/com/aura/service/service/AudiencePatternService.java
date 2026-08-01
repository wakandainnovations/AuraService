package com.aura.service.service;

import com.aura.service.dto.AudienceCohortPatternResponse;
import com.aura.service.dto.AudienceTimingPatternResponse;
import com.aura.service.enums.CohortGroupBy;

import java.time.Instant;

/**
 * Audience-pattern analytics for marketing: when a movie's (or a language's/industry's) audience is
 * most active, and how engagement compares across industry/language cohorts. Built on the same
 * mentions and per-platform engagement data as {@link MovieAudienceService}, but grouped for
 * pattern discovery across movies rather than a single movie's numbers.
 *
 * <p>Defined as an interface (mirroring {@link MovieAudienceService} / {@link GraphSyncService}) so
 * callers can mock it with an interface rather than a concrete class in unit tests.
 */
public interface AudiencePatternService {

    /**
     * Post volume, unique-author, and engagement totals bucketed by UTC hour-of-day and
     * day-of-week, plus the top-engagement (day, hour) slots, across every {@code MOVIE} entity
     * matching {@code language}/{@code industry}/{@code movieName} - at least one of the three is
     * required to scope the query. {@code from}/{@code to} default to the full history when omitted.
     */
    AudienceTimingPatternResponse getTimingPattern(
            String language, String industry, String movieName,
            Instant from, Instant to, Long requestedOwnerId);

    /**
     * Engagement totals for every tracked {@code MOVIE} entity, grouped by industry or language.
     * {@code from}/{@code to} default to the full history when omitted.
     */
    AudienceCohortPatternResponse getCohortPattern(
            CohortGroupBy groupBy, Instant from, Instant to, Long requestedOwnerId);
}
