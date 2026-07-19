package com.aura.service.service;

import com.aura.service.dto.IndianMacroSnapshot;

import java.time.LocalDate;

public interface IndianMacroEconomicDataService {

    /**
     * Looks up India's GDP and inflation rate for the year of {@code releaseDate}. Returns
     * {@code null} if {@code releaseDate} is null or no data exists for that year.
     */
    IndianMacroSnapshot lookup(LocalDate releaseDate);
}
