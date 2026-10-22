package com.aura.service.predictor;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * The UI's marketing-spend level, mapped to AuraPredictor's {@code ratingRange} band. Hindi films
 * sit one band higher than the rest at every level.
 */
public enum MarketingBudget {
    LOW(1_000, 100),
    MEDIUM(10_000, 1_000),
    HIGH(100_000, 10_000);

    private final int hindiRatingRange;
    private final int otherRatingRange;

    MarketingBudget(int hindiRatingRange, int otherRatingRange) {
        this.hindiRatingRange = hindiRatingRange;
        this.otherRatingRange = otherRatingRange;
    }

    public int ratingRange(boolean hindi) {
        return hindi ? hindiRatingRange : otherRatingRange;
    }

    /** Accepts "low"/"medium"/"high" in any case; blank means the default, MEDIUM. */
    @JsonCreator
    public static MarketingBudget fromJson(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("marketingBudget must be low, medium or high, got '" + value + "'");
        }
    }
}
