package com.aura.service.service;

import com.aura.service.entity.License;
import com.aura.service.enums.LicenseTier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Resolves the calling user's license and the limits it grants, and provides the admin license
 * management operations. All limit values are read from {@link LicenseTier} (the single source of
 * truth) — this service never invents them.
 *
 * <p>Deliberately price-free: this service is what regular users touch, so it has no knowledge of
 * pricing. Pricing lives behind {@link LicensePriceService}, which is admin-guarded.
 *
 * <p>Defined as an interface (mirroring {@link EntityAccessService}) so callers can mock it with an
 * interface rather than a concrete class in unit tests.
 */
public interface LicenseService {

    /** The active license of the authenticated user, or throws 404 if they have none. */
    License resolveCurrentLicense();

    /** The authenticated user's base (purchased) tier, ignoring any temporary override. */
    LicenseTier currentTier();

    /**
     * The authenticated user's <em>effective</em> tier: the temporary {@code overrideTier} from a
     * redeemed offer key when it is present and not past its {@code overrideExpiresAt}, otherwise the
     * base {@link #currentTier()}. This is the single tier every limit check (F4/F5) and feature gate
     * (F6) must consult — never the raw {@link #currentTier()}.
     */
    LicenseTier effectiveTier();

    int currentMaxKeywords();

    int currentMaxEntities();

    int currentMaxMentionsPerMonth();

    Duration currentCollectionFrequency();

    // ------------------------------------------------------------------
    // Admin operations (reachable only through the ROLE_ADMIN endpoints).
    // ------------------------------------------------------------------

    /**
     * Issues a new active license to {@code userId} at {@code tier}, generating a unique UUID-based
     * key. Any license the user already held is deactivated first, so a user has at most one active
     * license at a time. {@code expiresAt} may be null (never expires).
     */
    License issueLicense(Long userId, LicenseTier tier, Instant expiresAt);

    List<License> listLicenses();

    /** Partial update: change {@code tier} and/or {@code active}; a null argument is left unchanged. */
    License updateLicense(Long licenseId, LicenseTier tier, Boolean active);
}
