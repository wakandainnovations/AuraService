package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;

import java.time.Instant;

/**
 * Admin-facing view of a license. Deliberately carries the tier but <strong>never any price</strong>
 * — pricing is exposed only through the dedicated admin price-catalog endpoints.
 */
public record AdminLicenseSummary(
        Long id,
        String licenseKey,
        LicenseTier tier,
        Long userId,
        String username,
        boolean active,
        Instant issuedAt,
        Instant expiresAt
) {
}
