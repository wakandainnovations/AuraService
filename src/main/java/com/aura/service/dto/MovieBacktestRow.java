package com.aura.service.dto;

/**
 * One movie pulled from {@code movies_data_collection} for the box-office backtest. Field names
 * mirror the "Data available" block of the 100-factor prediction prompt; string fields hold the
 * raw column text (release_date is not always a full ISO date in that table — sometimes just a
 * year, occasionally malformed), so no date parsing is attempted here.
 */
public record MovieBacktestRow(
        String movieName,
        String releaseDate,
        String genre,
        String language,
        String releaseDay,
        Double gdpUsdBillions,
        Double inflationRatePct,
        Double budget,
        String productionCompanies,
        Integer runtime,
        String director,
        String cast,
        String synopsis,
        String firstSongReleaseDate,
        Long firstSongViews,
        String teaserReleaseDate,
        Long teaserViews,
        String trailerReleaseDate,
        Long trailerViews,
        Double actualRevenue,
        Double actualIndiaGross,
        Double actualDomesticGross,
        Double actualOverseasGross,
        Double actualFirstDayWorldwide) {
}
