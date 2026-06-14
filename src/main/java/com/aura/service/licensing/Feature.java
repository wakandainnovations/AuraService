package com.aura.service.licensing;

import com.aura.service.enums.LicenseTier;

/**
 * Catalog of the premium, tier-gated features. This enum is the <strong>single source of truth</strong>
 * for each feature's stable UI {@code key}, human-readable {@code displayName}, and minimum
 * {@link LicenseTier}. It drives both the per-endpoint entitlement checks and the
 * {@code GET /api/license/features} catalog, so a feature's required tier lives in exactly one place.
 *
 * <p>Deliberately price-free: a feature knows the tier it needs, never that tier's cost.
 */
public enum Feature {

    CHECKPOINTS("checkpoints", "Checkpoints", LicenseTier.SILVER),
    CRISIS("crisis", "Crisis Management", LicenseTier.GOLD),
    AUDIENCE_CONTENT("audience-content", "Audience & Content", LicenseTier.DIAMOND),
    INTELLIGENCE_REPORT("intelligence-report", "Intelligence Report", LicenseTier.DIAMOND),
    AGGREGATED_INTEL("aggregated-intel", "Aggregated Intel", LicenseTier.DIAMOND);

    private final String key;
    private final String displayName;
    private final LicenseTier requiredTier;

    Feature(String key, String displayName, LicenseTier requiredTier) {
        this.key = key;
        this.displayName = displayName;
        this.requiredTier = requiredTier;
    }

    /** Stable, machine-readable key the UI can switch on (e.g. {@code "checkpoints"}). */
    public String getKey() {
        return key;
    }

    /** Human-readable feature name (e.g. {@code "Crisis Management"}). */
    public String getDisplayName() {
        return displayName;
    }

    /** The minimum tier a non-admin user must hold to be entitled to this feature. */
    public LicenseTier getRequiredTier() {
        return requiredTier;
    }
}
