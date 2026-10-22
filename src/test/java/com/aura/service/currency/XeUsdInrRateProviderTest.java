package com.aura.service.currency;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class XeUsdInrRateProviderTest {

    @Test
    void parsesUsdRateFromXePage() {
        String page = "...\"historicRates\":[{\"currency\":\"EUR\",\"rate\":0.0091},"
                + "{\"currency\":\"USD\",\"rate\":0.010595440418211064},...";
        assertThat(XeUsdInrRateProvider.parseInrToUsd(page)).hasValue(0.010595440418211064);
    }

    @Test
    void missingUsdRate_isEmpty() {
        assertThat(XeUsdInrRateProvider.parseInrToUsd("<html>not found</html>")).isEmpty();
    }

    @Test
    void rateConvertsBothWays() {
        UsdInrRate rate = new UsdInrRate(LocalDate.of(2026, 10, 21), 0.0125);
        assertThat(rate.usdToInr(1_000)).isEqualTo(80_000);
        assertThat(rate.inrToUsd(80_000)).isEqualTo(1_000);
        assertThat(rate.usdToInrRate()).isEqualTo(80);
    }
}
