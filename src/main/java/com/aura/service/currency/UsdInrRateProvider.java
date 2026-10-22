package com.aura.service.currency;

/** The current USD/INR exchange rate. */
public interface UsdInrRateProvider {

    /** @throws ExchangeRateUnavailableException when no rate is stored and none can be fetched */
    UsdInrRate currentRate();
}
