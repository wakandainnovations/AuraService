package com.aura.service.service;

import com.aura.service.dto.UpdateLicenseTierPriceRequest;
import com.aura.service.entity.LicenseTierPrice;

import java.util.List;

/**
 * The admin-only price catalog for license tiers. Price data is sensitive and must never reach a
 * regular user, so {@link #listPrices()} and {@link #updatePrices(List)} both enforce that the
 * caller is an administrator (defense-in-depth on top of the {@code /api/admin/**} security rule and
 * the controller {@code @PreAuthorize}). Kept entirely separate from {@link LicenseService} so the
 * user-facing license flow structurally cannot expose a price.
 *
 * <p>Defined as an interface so callers can mock it with an interface rather than a concrete class.
 */
public interface LicensePriceService {

    /**
     * Ensures every {@link com.aura.service.enums.LicenseTier} has a price row, creating any missing
     * one at price 0. Intended for startup seeding; not admin-guarded (runs without a caller).
     */
    void seedDefaults();

    /** Admin-only: the full price catalog. Throws {@code AccessDeniedException} for non-admins. */
    List<LicenseTierPrice> listPrices();

    /** Admin-only: upsert the given tier prices and return the full catalog. */
    List<LicenseTierPrice> updatePrices(List<UpdateLicenseTierPriceRequest> updates);
}
