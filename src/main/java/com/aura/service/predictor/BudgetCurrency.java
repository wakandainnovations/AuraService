package com.aura.service.predictor;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/** Currency of the UI's {@code estimatedBudget}. AuraPredictor itself works in USD. */
public enum BudgetCurrency {
    INR,
    USD;

    /** Accepts "inr"/"usd" in any case; blank means the default, INR. */
    @JsonCreator
    public static BudgetCurrency fromJson(String value) {
        if (value == null || value.isBlank()) {
            return INR;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("currency must be INR or USD, got '" + value + "'");
        }
    }
}
