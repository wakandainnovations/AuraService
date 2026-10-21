package com.aura.service.predictor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One predicted revenue per assumed audience rating (out of 10). The model uses the audience
 * rating as an input and it isn't known before release, so there is no single prediction - show
 * these as scenarios. {@code low}/{@code high} are the model's typical out-of-sample error band,
 * not a guaranteed range. Revenue is in the same currency as the request's {@code budget}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpcomingMovieRevenueResponse(
        String movieName,
        String releaseDate,
        String language,
        String modelVersion,
        List<RatingPrediction> predictions,
        List<String> notes
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingPrediction(double rating, double revenue, double low, double high) {
    }
}
