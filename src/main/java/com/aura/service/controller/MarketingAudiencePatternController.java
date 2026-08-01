package com.aura.service.controller;

import com.aura.service.dto.AudienceCohortPatternResponse;
import com.aura.service.dto.AudienceTimingPatternResponse;
import com.aura.service.dto.EntitledResponse;
import com.aura.service.enums.CohortGroupBy;
import com.aura.service.licensing.Feature;
import com.aura.service.service.AudiencePatternService;
import com.aura.service.service.EntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Audience-pattern analytics for the marketing team: when tracked movies' audiences are most
 * active (for scheduling posts and campaigns) and how engagement compares across industry/language
 * cohorts (for allocating spend). Sits alongside {@link MarketingAggregationController} under
 * {@code /api/marketing} and shares its {@link Feature#AGGREGATED_INTEL} gate.
 */
@RestController
@RequestMapping("/api/marketing/audience-patterns")
@RequiredArgsConstructor
@Tag(name = "Marketing Audience Patterns",
        description = "Engagement timing and industry/language cohort patterns for marketing strategy")
public class MarketingAudiencePatternController {

    private final AudiencePatternService audiencePatternService;
    private final EntitlementService entitlementService;

    @Operation(summary = "Engagement bucketed by hour-of-day and day-of-week (UTC), "
            + "with top posting-time recommendations")
    @GetMapping("/timing")
    public EntitledResponse<AudienceTimingPatternResponse> getTimingPattern(
            @Parameter(description = "Filter by language (e.g. Tamil, Telugu)")
            @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry (e.g. Tollywood, Kollywood)")
            @RequestParam(required = false) String industry,
            @Parameter(description = "Filter to a single movie by name")
            @RequestParam(required = false) String movieName,
            @Parameter(description = "Only mentions posted on/after this instant")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Only mentions posted on/before this instant")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Admin-only: scope to a specific user's movies")
            @RequestParam(required = false) Long ownerId
    ) {
        if (!StringUtils.hasText(language) && !StringUtils.hasText(industry) && !StringUtils.hasText(movieName)) {
            throw new IllegalArgumentException(
                    "At least one filter is required: language, industry, or movieName");
        }
        return entitlementService.evaluate(Feature.AGGREGATED_INTEL, () ->
                audiencePatternService.getTimingPattern(language, industry, movieName, from, to, ownerId));
    }

    @Operation(summary = "Engagement totals across every tracked movie, grouped by industry or language")
    @GetMapping("/cohorts")
    public EntitledResponse<AudienceCohortPatternResponse> getCohortPattern(
            @Parameter(description = "Group movies by INDUSTRY or LANGUAGE")
            @RequestParam CohortGroupBy groupBy,
            @Parameter(description = "Only mentions posted on/after this instant")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Only mentions posted on/before this instant")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Admin-only: scope to a specific user's movies")
            @RequestParam(required = false) Long ownerId
    ) {
        return entitlementService.evaluate(Feature.AGGREGATED_INTEL, () ->
                audiencePatternService.getCohortPattern(groupBy, from, to, ownerId));
    }
}
