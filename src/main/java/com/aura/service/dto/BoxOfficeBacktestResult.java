package com.aura.service.dto;

import java.util.List;
import java.util.Map;

/**
 * The outcome of running one movie through the box-office prediction pipeline: the LLM supplies
 * only qualitative 1-5 (or "NA") ratings per catalog factor; everything from {@code baseline}
 * onward is computed server-side by {@code BoxOfficeBaselineServiceImpl}/
 * {@code BoxOfficeBacktestWorkerImpl} from those ratings plus real budget/GDP/cast/genre data -
 * see {@link BoxOfficeMovieBaseline} and {@code BoxOfficeFactorCatalog}. {@code error} is
 * non-null (and every other field but the identifying ones is null) when the LLM call or response
 * parsing failed for this movie - a single bad response never aborts the run.
 */
public record BoxOfficeBacktestResult(
        String movieName,
        String releaseDate,
        Double actualGrossUsd,
        String actualGrossSource,
        BoxOfficeMovieBaseline baseline,
        Double compoundMultiplier,
        Double predictedGrossUsd,
        Boolean withinTolerance,
        Double deviationPct,
        Map<String, Double> factorDeltas,
        List<String> postReleaseFactorsHelp,
        List<String> postReleaseFactorsHurt,
        String rationale,
        String error) {
}
