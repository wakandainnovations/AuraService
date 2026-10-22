package com.aura.service.predictor;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Body for AuraPredictor's {@code POST /v1/budget-risk/upcoming}; field names match its camelCase
 * JSON. Null fields are omitted so AuraPredictor applies its own defaults.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BudgetRiskUpstreamRequest(
        String movieName,
        String releaseDate,
        String language,
        Double budget,
        Double hitRatio,
        String genre,
        String cbfcRating,
        Boolean isRemake,
        Integer ratingRange,
        String productionCompany,
        String leadActor,
        List<String> supportingActors,
        String director,
        String musicDirector,
        List<Double> ratings
) {
}
