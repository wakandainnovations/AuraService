package com.aura.service.service;

import com.aura.service.dto.CreateOfferKeyRequest;
import com.aura.service.dto.UpdateOfferKeyRequest;
import com.aura.service.entity.License;
import com.aura.service.entity.OfferKey;

import java.util.List;

/**
 * Admin management of {@code OfferKey}s plus the user-facing redemption flow. Redeeming a key grants
 * the calling user a temporary tier override on their active license (see {@link LicenseService}),
 * raising both their feature gates and numeric limits to the key's granted tier until it expires.
 *
 * <p>Like {@link LicenseService}, this is deliberately <strong>price-free</strong> and is defined as an
 * interface so callers can mock it with an interface rather than a concrete class in unit tests.
 */
public interface OfferKeyService {

    // ------------------------------------------------------------------
    // Admin CRUD (reachable only through the ROLE_ADMIN endpoints).
    // ------------------------------------------------------------------

    /** Creates a new offer key; rejects a duplicate {@code code} with a 400. */
    OfferKey create(CreateOfferKeyRequest request);

    List<OfferKey> list();

    /** Loads one offer key by id, or throws 404. */
    OfferKey get(Long id);

    /** Partial update: applies only the non-null fields of {@code request}. */
    OfferKey update(Long id, UpdateOfferKeyRequest request);

    void delete(Long id);

    // ------------------------------------------------------------------
    // User redemption.
    // ------------------------------------------------------------------

    /**
     * Redeems the key with {@code code} for the authenticated user: validates it (exists, active, not
     * expired, redemptions remaining), sets the override on the user's active license, and increments
     * the key's redemption count. Throws {@code OfferKeyRedemptionException} (→ 400) for an
     * invalid/inactive/expired/exhausted key. Returns the updated license.
     */
    License redeem(String code);
}
