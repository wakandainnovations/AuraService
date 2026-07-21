package com.aura.service.dto;

import java.util.List;

/**
 * The outcome of running one movie through the box-office prediction prompt and checking the
 * prediction against what {@code movies_data_collection} recorded actually happened.
 * {@code error} is non-null (and every other field but the identifying ones is null) when the LLM
 * call or response parsing failed for this movie — a single bad response never aborts the run.
 */
public record BoxOfficeBacktestResult(
        String movieName,
        String releaseDate,
        Double actualGrossUsd,
        String actualGrossSource,
        Double predictedLowUsd,
        Double predictedHighUsd,
        String predictedRangeRaw,
        Boolean withinPredictedRange,
        Double deviationPct,
        List<String> upsideFactorsCited,
        List<String> downsideFactorsCited,
        String finalVerdict,
        String error) {
}
