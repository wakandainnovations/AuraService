package com.aura.service.service;

import com.aura.service.dto.EntitledResponse;
import com.aura.service.dto.FeatureCatalogEntry;
import com.aura.service.enums.LicenseTier;
import com.aura.service.licensing.Feature;

import java.util.List;
import java.util.function.Supplier;

/**
 * Decides whether the current user may use a premium {@link Feature} and shapes the response into an
 * {@link EntitledResponse} accordingly. This is what replaced the old hard-{@code 403} gate.
 *
 * <p>It reuses the single entitlement rule (F6/F7): a holder of {@code ROLE_ADMIN} is always entitled,
 * otherwise the user's {@link LicenseService#effectiveTier() effective tier} must be at least the
 * feature's required tier (so a redeemed offer-key override counts toward reaching a feature).
 *
 * <p>Defined as an interface (mirroring {@link LicenseService} / {@link EntityAccessService}) so callers
 * can mock it with an interface rather than a concrete class in unit tests.
 */
public interface EntitlementService {

    /** True if the current user may use a feature requiring {@code requiredTier} (admin, or tier high enough). */
    boolean isEntitled(LicenseTier requiredTier);

    /**
     * For non-mutating endpoints. Always computes the real payload via {@code realPayload}; returns it
     * as {@code data} when the user is entitled, otherwise returns a masked teaser as {@code preview}
     * (with {@code data == null}).
     */
    <T> EntitledResponse<T> evaluate(Feature feature, Supplier<T> realPayload);

    /**
     * Like {@link #evaluate} but for a payload the caller has <em>already</em> computed (e.g. because it
     * also needs it for a non-JSON rendering): wraps it entitled, or masks it into a locked preview.
     */
    <T> EntitledResponse<T> wrap(Feature feature, T payload);

    /**
     * For mutating / side-effecting endpoints. Runs {@code action} (and returns its result as
     * {@code data}) only when the user is entitled; otherwise short-circuits — the action never runs —
     * to a locked envelope with no preview.
     */
    <T> EntitledResponse<T> gate(Feature feature, Supplier<T> action);

    /** The full feature catalog with the current user's entitlement for each. Price-free. */
    List<FeatureCatalogEntry> catalog();
}
