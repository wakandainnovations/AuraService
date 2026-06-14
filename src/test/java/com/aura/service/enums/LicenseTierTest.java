package com.aura.service.enums;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the per-tier limit values — these constants are the single source of truth for every
 * limit check, so a change here is a deliberate product decision, not an accident.
 */
class LicenseTierTest {

    @Test
    void bronzeLimits() {
        assertThat(LicenseTier.BRONZE.getMaxKeywords()).isEqualTo(5);
        assertThat(LicenseTier.BRONZE.getMaxEntities()).isEqualTo(5);
        assertThat(LicenseTier.BRONZE.getMaxMentionsPerMonth()).isEqualTo(2_000);
        assertThat(LicenseTier.BRONZE.getCollectionFrequency()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void silverLimits() {
        assertThat(LicenseTier.SILVER.getMaxKeywords()).isEqualTo(10);
        assertThat(LicenseTier.SILVER.getMaxEntities()).isEqualTo(10);
        assertThat(LicenseTier.SILVER.getMaxMentionsPerMonth()).isEqualTo(10_000);
        assertThat(LicenseTier.SILVER.getCollectionFrequency()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void goldLimits() {
        assertThat(LicenseTier.GOLD.getMaxKeywords()).isEqualTo(15);
        assertThat(LicenseTier.GOLD.getMaxEntities()).isEqualTo(15);
        assertThat(LicenseTier.GOLD.getMaxMentionsPerMonth()).isEqualTo(40_000);
        assertThat(LicenseTier.GOLD.getCollectionFrequency()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void diamondLimits() {
        assertThat(LicenseTier.DIAMOND.getMaxKeywords()).isEqualTo(25);
        assertThat(LicenseTier.DIAMOND.getMaxEntities()).isEqualTo(20);
        assertThat(LicenseTier.DIAMOND.getMaxMentionsPerMonth()).isEqualTo(100_000);
        assertThat(LicenseTier.DIAMOND.getCollectionFrequency()).isEqualTo(Duration.ofMinutes(10));
    }
}
