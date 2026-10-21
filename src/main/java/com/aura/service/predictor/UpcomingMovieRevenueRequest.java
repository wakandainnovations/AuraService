package com.aura.service.predictor;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Inputs for AuraPredictor's {@code POST /v1/predict/upcoming} - a movie that isn't in
 * {@code india_movies_data_collection} yet. Field names match that API's camelCase JSON one to one.
 *
 * <p>{@code runtime} is required (not just {@code budget}) because every training movie has one;
 * leaving it blank reads to the model as "shorter than any real film" and drags the prediction
 * down ~2.5x. Fewer than 4 {@code supportingActors} also lowers it - the response's {@code notes}
 * say so whenever an input had to be approximated. Null fields are omitted so AuraPredictor applies
 * its own defaults ({@code ratings} 4/6/8, {@code ratingRange} by language, release event by date).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpcomingMovieRevenueRequest(
        @NotBlank String movieName,
        @NotBlank String releaseDate,
        @NotBlank String language,
        @NotNull @Positive Double budget,
        @NotNull @Positive Double runtime,
        String genre,
        String cbfcRating,
        @Positive Integer numberOfScreens,
        String releaseEventType,
        String releaseEventName,
        Boolean isRemake,
        Integer ratingRange,
        String productionCompany,
        String leadActor,
        @Size(max = 4) List<String> supportingActors,
        String director,
        String musicDirector,
        @Size(min = 1, max = 10) List<@NotNull @DecimalMin("0") @DecimalMax("10") Double> ratings
) {
}
