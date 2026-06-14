package com.aura.service.enums;

import java.time.Duration;

/**
 * The four licensing tiers a {@code License} can hold, each pinning the fixed per-tier limits that
 * govern how much of the product a user may consume. These enum constants are the <strong>single
 * source of truth</strong> for every limit check in the system — no limit value should ever be
 * hard-coded elsewhere; read it from the tier instead.
 *
 * <p>Note that prices are deliberately <em>not</em> modelled here: pricing lives in the
 * {@code license_tier_prices} table and is admin-only, so it must never travel with the limits that
 * regular users are allowed to see.
 */
public enum LicenseTier {

    BRONZE(5, 5, 2_000, Duration.ofHours(24)),
    SILVER(10, 10, 10_000, Duration.ofHours(12)),
    GOLD(15, 15, 40_000, Duration.ofHours(1)),
    DIAMOND(25, 20, 100_000, Duration.ofMinutes(10));

    /** Maximum number of keywords a user on this tier may track across their entities. */
    private final int maxKeywords;

    /** Maximum number of managed entities a user on this tier may own. */
    private final int maxEntities;

    /** Maximum number of mentions that may be collected for the user within a calendar month. */
    private final int maxMentionsPerMonth;

    /** How often the ingestion pipeline collects mentions for this tier (shorter = fresher data). */
    private final Duration collectionFrequency;

    LicenseTier(int maxKeywords, int maxEntities, int maxMentionsPerMonth, Duration collectionFrequency) {
        this.maxKeywords = maxKeywords;
        this.maxEntities = maxEntities;
        this.maxMentionsPerMonth = maxMentionsPerMonth;
        this.collectionFrequency = collectionFrequency;
    }

    public int getMaxKeywords() {
        return maxKeywords;
    }

    public int getMaxEntities() {
        return maxEntities;
    }

    public int getMaxMentionsPerMonth() {
        return maxMentionsPerMonth;
    }

    public Duration getCollectionFrequency() {
        return collectionFrequency;
    }
}
