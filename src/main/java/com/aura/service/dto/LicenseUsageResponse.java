package com.aura.service.dto;

/**
 * The authenticated user's current usage against their tier's caps, for usage meters in the UI:
 * entities used vs max, and keywords used (summed across all their entities) vs max.
 *
 * <p>Carries <strong>no price</strong> — limits are user-facing, but pricing is admin-only and is
 * never surfaced here. The maxima come straight from {@link com.aura.service.enums.LicenseTier}.
 */
public record LicenseUsageResponse(
        long entitiesUsed,
        int entitiesMax,
        long keywordsUsed,
        int keywordsMax
) {
}
