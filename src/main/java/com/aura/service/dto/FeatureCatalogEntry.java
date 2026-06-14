package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;

/**
 * One row of {@code GET /api/license/features}: a premium feature plus whether the current user is
 * entitled to it, so the UI can render every feature up-front and lock-badge the ones the user's tier
 * hasn't unlocked. Deliberately price-free: it names the required tier, never its cost.
 */
public record FeatureCatalogEntry(
        String key,
        String name,
        LicenseTier requiredTier,
        boolean entitled
) {}
