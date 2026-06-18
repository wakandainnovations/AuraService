package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
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

    /** True if the current user holds {@code ROLE_ADMIN}. Admins may read every user's entities. */
    boolean currentUserIsAdmin();

    /**
     * The 403 gate for the optional admin {@code ownerId} view-scoping param: scoping a listing to a
     * specific user is an admin-only capability, so a non-admin who supplies any {@code ownerId} is
     * rejected with {@link org.springframework.security.access.AccessDeniedException} (→ 403). A null
     * {@code requestedOwnerId} (the regular case) is always allowed.
     */
    void requireAdminToScopeByOwner(Long requestedOwnerId);

    /**
     * Resolves the owner-id a <em>listing</em> should be filtered by, applying the admin rules
     * (calls {@link #requireAdminToScopeByOwner} first):
     * <ul>
     *   <li>non-admin → the caller's own id (a user only ever lists their own entities);</li>
     *   <li>admin + {@code ownerId} → that {@code ownerId} (scope the view to one user);</li>
     *   <li>admin + {@code null} → {@code null}, meaning "no owner filter" / all entities.</li>
     * </ul>
     */
    Long resolveOwnerScope(Long requestedOwnerId);

    /**
     * Returns the entity if the current user may access it, otherwise throws
     * {@link com.aura.service.exception.ResourceNotFoundException} (→ 404). Applies the admin rules
     * (calls {@link #requireAdminToScopeByOwner} first):
     * <ul>
     *   <li>non-admin → the entity must be owned by the caller (else 404);</li>
     *   <li>admin + {@code ownerId} → the entity must be owned by {@code ownerId} (else 404), so an
     *       admin scoped to one user can't reach another user's entity;</li>
     *   <li>admin + {@code null} → any existing entity is accessible (bypass).</li>
     * </ul>
     * A missing entity is always a 404 (checked even for admins), and missing/not-owned stay
     * intentionally indistinguishable so the API never leaks the existence of other users' entities.
     */
    ManagedEntity assertAccessible(Long entityId, Long requestedOwnerId);

    /**
     * Convenience for the many entity-keyed call sites that don't take an {@code ownerId}: equivalent
     * to {@link #assertAccessible(Long, Long) assertAccessible(entityId, null)}. Admins bypass the
     * ownership check here too, so every guard routed through this method honors admin access.
     */
    ManagedEntity assertOwnedByCurrentUser(Long entityId);

    /**
     * Access guard for a {@link Mention}, which may now be attributed to several entities. Returns the
     * linked entity the caller is allowed to act through, so callers get both the check and the
     * contextual entity in one call. Applies the same admin rules as {@link #assertAccessible}:
     * <ul>
     *   <li>non-admin → the entity the caller owns among the mention's links (else 404);</li>
     *   <li>admin + {@code ownerId} → the link owned by {@code ownerId} (else 404);</li>
     *   <li>admin + {@code null} → any linked entity (the first).</li>
     * </ul>
     * Throws {@link com.aura.service.exception.ResourceNotFoundException} (→ 404) when no linked entity
     * satisfies the rule, keeping not-owned and absent indistinguishable.
     */
    ManagedEntity assertMentionAccessible(Mention mention, Long requestedOwnerId);

    /** Convenience for the no-{@code ownerId} call sites: {@code assertMentionAccessible(mention, null)}. */
    default ManagedEntity assertMentionAccessible(Mention mention) {
        return assertMentionAccessible(mention, null);
    }
}
