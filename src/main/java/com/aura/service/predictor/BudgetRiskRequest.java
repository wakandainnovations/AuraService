package com.aura.service.predictor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * What the UI sends for a budget-risk check on a planned movie. {@link BudgetRiskService} turns it
 * into AuraPredictor's {@code POST /v1/budget-risk/upcoming} request: {@code estimatedBudget}, in
 * {@code currency} (default INR), becomes {@code budget} in USD, and {@code marketingBudget} (default medium) plus the lead actor's recent
 * films decide {@code ratingRange} and {@code ratings}.
 */
public record BudgetRiskRequest(
        @NotBlank String movieName,
        // Also compared as a string against india_movies_data_collection.release_date, so it must be ISO.
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "must be a date as YYYY-MM-DD") String releaseDate,
        @NotBlank String language,
        @Positive Double estimatedBudget,
        BudgetCurrency currency,
        String genre,
        String productionCompany,
        String cbfcRating,
        Boolean isRemake,
        MarketingBudget marketingBudget,
        String leadActor,
        @Size(max = 4) List<String> supportingActors,
        String director,
        String musicDirector
) {
}
