package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;

/**
 * Central authority for "who is calling" and "may they touch this entity". Every place that loads a
 * {@link ManagedEntity} by id must route the ownership check through {@link #assertOwnedByCurrentUser}
 * so the rule lives in exactly one place.
 *
 * <p>Defined as an interface (mirroring {@link LLMService} / {@link SocialMediaService}) so callers can
 * mock it with an interface rather than a concrete class in unit tests.
 */
public interface EntityAccessService {

    /** The authenticated user resolved from the security context, or throws if none can be resolved. */
    User currentUser();

    /**
     * Returns the entity if it exists and is owned by the current user; otherwise throws
     * {@link com.aura.service.exception.ResourceNotFoundException} (→ 404). Missing and not-owned are
     * intentionally indistinguishable so the API never leaks the existence of other users' entities.
     */
    ManagedEntity assertOwnedByCurrentUser(Long entityId);
}
