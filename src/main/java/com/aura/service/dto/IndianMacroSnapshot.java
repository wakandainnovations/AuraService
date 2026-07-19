package com.aura.service.dto;

/**
 * India's macro-economic backdrop for a given movie release date: GDP and inflation rate for
 * the release year, looked up from the historical data in {@code movies_data_collection}. Either
 * field is {@code null} when no data exists for that year.
 */
public record IndianMacroSnapshot(Double gdpUsdBillions, Double inflationRatePct) {
}
