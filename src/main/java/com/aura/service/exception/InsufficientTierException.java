package com.aura.service.exception;

import com.aura.service.enums.LicenseTier;

/**
 * Thrown by the premium-feature gate when a non-admin user's {@link LicenseTier} is below the minimum
 * required by a {@code @RequiresTier}-annotated endpoint. Mapped to {@code 403 Forbidden} (see
 * {@link GlobalExceptionHandler}) with the structured body {@code { feature, requiredTier }} so the UI
 * can tell the user exactly which capability is gated and what tier unlocks it.
 *
 * <p>Like {@link LimitException}, this carries <strong>no price/cost information</strong>: it names the
 * required tier, never what that tier costs.
 */
public class InsufficientTierException extends RuntimeException {

    /** The premium feature that was gated; surfaced verbatim in the 403 body's {@code feature}. */
    private final String feature;

    /** The minimum tier that would unlock the feature; surfaced in the 403 body's {@code requiredTier}. */
    private final LicenseTier requiredTier;

    public InsufficientTierException(String feature, LicenseTier requiredTier) {
        super(feature + " requires the " + requiredTier + " tier");
        this.feature = feature;
        this.requiredTier = requiredTier;
    }

    public String getFeature() {
        return feature;
    }

    public LicenseTier getRequiredTier() {
        return requiredTier;
    }
}
