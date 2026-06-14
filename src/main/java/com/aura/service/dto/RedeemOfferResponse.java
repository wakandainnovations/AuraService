package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;

import java.time.Instant;

/**
 * Confirms a successful offer-key redemption: the user's base (purchased) tier, the tier now granted
 * by the override, the resulting effective tier, and when the override lapses (null = no expiry).
 * Carries <strong>no price</strong> — like every user-facing license payload, it never exposes cost.
 */
public record RedeemOfferResponse(
        LicenseTier baseTier,
        LicenseTier overrideTier,
        LicenseTier effectiveTier,
        Instant overrideExpiresAt
) {
}
