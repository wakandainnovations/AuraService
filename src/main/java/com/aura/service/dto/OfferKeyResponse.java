package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;

import java.time.Instant;

/**
 * Admin view of an offer key. Carries <strong>no price</strong> — an offer key grants access, not a
 * purchase, so there is nothing cost-related to expose.
 */
public record OfferKeyResponse(
        Long id,
        String code,
        LicenseTier grantsTier,
        boolean active,
        Instant expiresAt,
        Integer maxRedemptions,
        int redemptionCount
) {
}
