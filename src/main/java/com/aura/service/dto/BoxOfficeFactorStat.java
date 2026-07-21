package com.aura.service.dto;

/**
 * End-of-run aggregate for one of the 103 catalog factors: how often the LLM cited it as an
 * upside/downside multiplier across the backtest, and how often that citation was borne out by
 * the actual gross (an "upside" factor is confirmed when the movie's actual gross met-or-beat the
 * predicted low; a "downside" factor is confirmed when actual gross did not exceed the predicted
 * high). A factor cited often but rarely confirmed is a candidate for a smaller stated impact
 * range in the prompt catalog; this is reporting only — no range is changed automatically.
 */
public record BoxOfficeFactorStat(
        String factor,
        int citedAsUpsideCount,
        int upsideConfirmedCount,
        int citedAsDownsideCount,
        int downsideConfirmedCount) {

    public double upsideConfirmationRate() {
        return citedAsUpsideCount == 0 ? 0.0 : (double) upsideConfirmedCount / citedAsUpsideCount;
    }

    public double downsideConfirmationRate() {
        return citedAsDownsideCount == 0 ? 0.0 : (double) downsideConfirmedCount / citedAsDownsideCount;
    }
}
