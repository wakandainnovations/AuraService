package com.aura.service.controller;

import com.aura.service.service.EntityAccessService;
import com.aura.service.service.MarketingAggregationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/marketing/aggregate")
@RequiredArgsConstructor
@Tag(name = "Marketing Aggregation",
        description = "Aggregate marketing data across keywords by language, industry, entity, etc.")
public class MarketingAggregationController {

    private final MarketingAggregationService service;
    private final EntityAccessService entityAccessService;

    @Operation(summary = "Get aggregated top spreaders across matching keywords")
    @GetMapping("/top-spreaders")
    public ResponseEntity<Object> topSpreaders(
            @Parameter(description = "Filter by language (e.g. Tamil, Telugu)") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry (e.g. Tollywood, Kollywood)") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by state") @RequestParam(required = false) String state,
            @Parameter(description = "Filter by genre (e.g. action, drama)") @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by entity ID") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Group results by keyword") @RequestParam(required = false) String groupBy
    ) {
        validateAtLeastOneFilter(language, industry, state, genre, entityId);
        boolean grouped = "keyword".equalsIgnoreCase(groupBy);
        return ResponseEntity.ok(service.getAggregatedTopSpreaders(
                language, industry, state, genre, entityId, grouped));
    }

    @Operation(summary = "Get aggregated viral seeds across matching keywords")
    @GetMapping("/viral-seeds")
    public ResponseEntity<Object> viralSeeds(
            @Parameter(description = "Filter by language") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by state") @RequestParam(required = false) String state,
            @Parameter(description = "Filter by genre") @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by entity ID") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Group results by keyword") @RequestParam(required = false) String groupBy
    ) {
        validateAtLeastOneFilter(language, industry, state, genre, entityId);
        boolean grouped = "keyword".equalsIgnoreCase(groupBy);
        return ResponseEntity.ok(service.getAggregatedViralSeeds(
                language, industry, state, genre, entityId, grouped));
    }

    @Operation(summary = "Get aggregated aspect drivers across matching keywords")
    @GetMapping("/aspect-drivers")
    public ResponseEntity<Object> aspectDrivers(
            @Parameter(description = "Filter by language") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by state") @RequestParam(required = false) String state,
            @Parameter(description = "Filter by genre") @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by entity ID") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Group results by keyword") @RequestParam(required = false) String groupBy
    ) {
        validateAtLeastOneFilter(language, industry, state, genre, entityId);
        boolean grouped = "keyword".equalsIgnoreCase(groupBy);
        return ResponseEntity.ok(service.getAggregatedAspectDrivers(
                language, industry, state, genre, entityId, grouped));
    }

    @Operation(summary = "Get aggregated brand evangelists across matching keywords")
    @GetMapping("/brand-evangelists")
    public ResponseEntity<Object> brandEvangelists(
            @Parameter(description = "Filter by language") @RequestParam(required = false) String language,
            @Parameter(description = "Filter by industry") @RequestParam(required = false) String industry,
            @Parameter(description = "Filter by state") @RequestParam(required = false) String state,
            @Parameter(description = "Filter by genre") @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by entity ID") @RequestParam(required = false) Long entityId,
            @Parameter(description = "Group results by keyword") @RequestParam(required = false) String groupBy
    ) {
        validateAtLeastOneFilter(language, industry, state, genre, entityId);
        boolean grouped = "keyword".equalsIgnoreCase(groupBy);
        return ResponseEntity.ok(service.getAggregatedBrandEvangelists(
                language, industry, state, genre, entityId, grouped));
    }

    @Operation(summary = "Get aggregated genre data (potential-viewers, super-spreaders, or channel-strategy)")
    @GetMapping("/genre/{subType}")
    public ResponseEntity<Object> genreData(
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
            return ResponseEntity.badRequest().body(
                    java.util.Map.of("error", "subType must be one of: potential-viewers, super-spreaders, channel-strategy"));
        }
        boolean grouped = "genre".equalsIgnoreCase(groupBy);
        return ResponseEntity.ok(service.getAggregatedGenreData(
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
}
