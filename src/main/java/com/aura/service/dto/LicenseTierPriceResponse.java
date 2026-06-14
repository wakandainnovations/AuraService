package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Admin-only projection of a price-catalog row. This is the <em>only</em> DTO in the system that
 * carries a price, and it is returned exclusively by the admin price endpoints under
 * {@code /api/admin/license-prices}.
 */
public record LicenseTierPriceResponse(
        LicenseTier tier,
        BigDecimal price,
        String currency,
        Instant updatedAt
) {
}
