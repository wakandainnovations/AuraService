package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;

/**
 * The current user's own license view: their tier and the per-tier limits that govern their usage.
 * Carries <strong>no price</strong> — pricing is admin-only and never surfaced to regular users.
 * {@code collectionFrequency} is the ISO-8601 duration string (e.g. {@code "PT24H"}, {@code "PT10M"}).
 */
public record MyLicenseResponse(
        LicenseTier tier,
        int maxKeywords,
        int maxEntities,
        int maxMentionsPerMonth,
        String collectionFrequency
) {
}
