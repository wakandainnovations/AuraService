package com.aura.service.currency;

import java.time.LocalDate;

/**
 * One day's exchange rate, held the way {@code currency_rate_xe} stores it: rupees to dollars
 * ({@code from_currency = INR}, {@code to_currency = USD}).
 */
public record UsdInrRate(LocalDate rateDate, double inrToUsd) {

    public double usdToInr(double usd) {
        return usd / inrToUsd;
    }

    public double inrToUsd(double inr) {
        return inr * inrToUsd;
    }

    /** Rupees per dollar. */
    public double usdToInrRate() {
        return 1 / inrToUsd;
    }
}
