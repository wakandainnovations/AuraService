package com.aura.service.controller;

import com.aura.service.dto.EntitledResponse;
import com.aura.service.licensing.Feature;
import com.aura.service.service.EntitlementService;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.MarketingAggregationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Aggregated Intel — a {@link Feature#AGGREGATED_INTEL DIAMOND}-tier feature. Under-tier users are no
 * longer rejected with a {@code 403}; each endpoint answers {@code 200} with an {@link EntitledResponse}
 * carrying either the real aggregation (entitled) or a masked, blurred teaser (not entitled). Request
 * validation (filters, sub-type, entity ownership) still applies to everyone, before entitlement.
 */
@RestController
@RequestMapping("/api/marketing/aggregate")
@RequiredArgsConstructor
@Tag(name = "Marketing Aggregation",
        description = "Aggregate marketing data across keywords by language, industry, entity, etc.")
public class MarketingAggregationController {

    private static final Set<String> VALID_SPREADER_PLATFORMS = Set.of("x", "youtube", "reddit", "instagram");

    private final MarketingAggregationService service;
    private final EntityAccessService entityAccessService;
    private final EntitlementService entitlementService;

    @Operation(summary = "Get aggregated top spreaders across matching keywords")
    @GetMapping("/top-spreaders")
    public EntitledResponse<Object> topSpreaders(
            @Parameter(description = "Filter by language (e.g. Tamil, Telugu)") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry (e.g. Tollywood, Kollywood)") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by state") @RequestParam(required = false) String state,
            @Parameter(description = "Filter by genre (e.g. action, drama)") @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by entity ID") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Group results by keyword") @RequestParam(required = false) String groupBy,
            @Parameter(description = "Restrict results to one platform: x, youtube, reddit, or instagram") @RequestParam(required = false) String platform
    ) {
        validateAtLeastOneFilter(language, industry, state, genre, entityId);
        String normalizedPlatform = validatePlatform(platform);
        boolean grouped = "keyword".equalsIgnoreCase(groupBy);
        return entitlementService.evaluate(Feature.AGGREGATED_INTEL, () -> service.getAggregatedTopSpreaders(
                language, industry, state, genre, entityId, grouped, normalizedPlatform));
    }

    @Operation(summary = "Get aggregated viral seeds across matching keywords")
    @GetMapping("/viral-seeds")
    public EntitledResponse<Object> viralSeeds(
            @Parameter(description = "Filter by language") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by state") @RequestParam(required = false) String state,
            @Parameter(description = "Filter by genre") @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by entity ID") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Group results by keyword") @RequestParam(required = false) String groupBy
    ) {
        validateAtLeastOneFilter(language, industry, state, genre, entityId);
        boolean grouped = "keyword".equalsIgnoreCase(groupBy);
        return entitlementService.evaluate(Feature.AGGREGATED_INTEL, () -> service.getAggregatedViralSeeds(
                language, industry, state, genre, entityId, grouped));
    }

    @Operation(summary = "Get aggregated aspect drivers across matching keywords")
    @GetMapping("/aspect-drivers")
    public EntitledResponse<Object> aspectDrivers(
            @Parameter(description = "Filter by language") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by state") @RequestParam(required = false) String state,
            @Parameter(description = "Filter by genre") @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by entity ID") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Group results by keyword") @RequestParam(required = false) String groupBy
    ) {
        validateAtLeastOneFilter(language, industry, state, genre, entityId);
        boolean grouped = "keyword".equalsIgnoreCase(groupBy);
        return entitlementService.evaluate(Feature.AGGREGATED_INTEL, () -> service.getAggregatedAspectDrivers(
                language, industry, state, genre, entityId, grouped));
    }

    @Operation(summary = "Get aggregated movie buffs across matching keywords")
    @GetMapping("/movie-buffs")
    public EntitledResponse<Object> movieBuffs(
            @Parameter(description = "Filter by language") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by state") @RequestParam(required = false) String state,
            @Parameter(description = "Filter by genre") @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by entity ID") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Group results by keyword") @RequestParam(required = false) String groupBy
    ) {
        validateAtLeastOneFilter(language, industry, state, genre, entityId);
        boolean grouped = "keyword".equalsIgnoreCase(groupBy);
        return entitlementService.evaluate(Feature.AGGREGATED_INTEL, () -> service.getAggregatedMovieBuffs(
                language, industry, state, genre, entityId, grouped));
    }

    @Operation(summary = "Get aggregated genre data (potential-viewers, super-spreaders, or channel-strategy)")
    @GetMapping("/genre/{subType}")
    public EntitledResponse<Object> genreData(
            @Parameter(description = "Genre sub-type: potential-viewers, super-spreaders, or channel-strategy")
            @PathVariable String subType,
            @Parameter(description = "Filter by language") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by state") @RequestParam(required = false) String state,
            @Parameter(description = "Filter by genre") @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by entity ID") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Group results by genre") @RequestParam(required = false) String groupBy
    ) {
        validateAtLeastOneFilter(language, industry, state, genre, entityId);
        if (!"potential-viewers".equals(subType)
                && !"super-spreaders".equals(subType)
                && !"channel-strategy".equals(subType)) {
            throw new IllegalArgumentException(
                    "subType must be one of: potential-viewers, super-spreaders, channel-strategy");
        }
        boolean grouped = "genre".equalsIgnoreCase(groupBy);
        return entitlementService.evaluate(Feature.AGGREGATED_INTEL, () -> service.getAggregatedGenreData(
                subType, language, industry, state, genre, entityId, grouped));
    }

    private void validateAtLeastOneFilter(String language, String industry,
                                          String state, String genre, Long entityId) {
        if (language == null && industry == null && state == null
                && genre == null && entityId == null) {
            throw new IllegalArgumentException(
                    "At least one filter is required: language, industry, state, genre, or entityId");
        }
        // When the aggregation is scoped to a specific entity, that entity must belong to the caller.
        if (entityId != null) {
            entityAccessService.assertOwnedByCurrentUser(entityId);
        }
    }

    private static String validatePlatform(String platform) {
        if (platform == null) {
            return null;
        }
        String normalized = platform.toLowerCase();
        if (!VALID_SPREADER_PLATFORMS.contains(normalized)) {
            throw new IllegalArgumentException("platform must be one of: x, youtube, reddit, instagram");
        }
        return normalized;
    }
}
