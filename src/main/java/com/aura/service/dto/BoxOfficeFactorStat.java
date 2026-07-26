package com.aura.service.dto;

/**
 * End-of-run aggregate for one catalog factor: how often the LLM felt it had a basis to rate it
 * (vs. answering "NA") across the backtest, and the average delta it assigned when it did rate.
 * A factor rated "NA" on nearly every movie is a candidate to drop from the LLM-rated set
 * entirely (server-compute it, or accept it as genuinely unjudgeable from this dataset) - this is
 * reporting only, no range or role is changed automatically.
 */
public record BoxOfficeFactorStat(
        int factorNumber,
        String factorName,
        int ratedCount,
        int naCount,
        double avgDeltaWhenRated) {
}
