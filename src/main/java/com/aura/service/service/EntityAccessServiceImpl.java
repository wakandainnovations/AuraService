package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.User;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EntityAccessServiceImpl implements EntityAccessService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final ManagedEntityRepository entityRepository;
    private final UserRepository userRepository;

    @Override
    public User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || !auth.isAuthenticated()) {
            throw new ResourceNotFoundException("No authenticated user");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Authenticated user not found: " + auth.getName()));
    }

    @Override
    public boolean currentUserIsAdmin() {
        return ROLE_ADMIN.equals(currentUser().getRole());
    }

    @Override
    public void requireAdminToScopeByOwner(Long requestedOwnerId) {
        if (requestedOwnerId != null && !currentUserIsAdmin()) {
            // Scoping a view to another user is an admin-only capability.
            throw new AccessDeniedException("Only administrators may scope by ownerId");
        }
    }

    @Override
    public Long resolveOwnerScope(Long requestedOwnerId) {
        User user = currentUser();
        if (!ROLE_ADMIN.equals(user.getRole())) {
            // Non-admins may not pass an ownerId, and always list only their own entities.
            if (requestedOwnerId != null) {
                throw new AccessDeniedException("Only administrators may scope by ownerId");
            }
            return user.getId();
        }
        // Admin: a specific ownerId narrows to that user; null means "all entities" (no filter).
        return requestedOwnerId;
    }

    @Override
    public ManagedEntity assertAccessible(Long entityId, Long requestedOwnerId) {
        User user = currentUser();
        boolean admin = ROLE_ADMIN.equals(user.getRole());
        if (requestedOwnerId != null && !admin) {
            // Reject (403) before any lookup so a non-admin can't probe entity existence via ownerId.
            throw new AccessDeniedException("Only administrators may scope by ownerId");
        }

        ManagedEntity entity = entityId == null ? null
                : entityRepository.findById(entityId).orElse(null);
        if (entity == null) {
            // A missing entity is always a 404, even for admins.
            throw new ResourceNotFoundException("Entity not found with id: " + entityId);
        }

        // The owner the entity must belong to: the requested one for an admin scoped to a user,
        // the caller themselves for a non-admin. An unscoped admin (null) may reach any entity.
        Long requiredOwnerId = admin ? requestedOwnerId : user.getId();
        if (requiredOwnerId != null
                && (entity.getOwner() == null || !requiredOwnerId.equals(entity.getOwner().getId()))) {
            // Same 404 whether the entity is absent or simply someone else's — never leak existence.
            throw new ResourceNotFoundException("Entity not found with id: " + entityId);
        }
        return entity;
    }

    @Override
    public ManagedEntity assertOwnedByCurrentUser(Long entityId) {
        return assertAccessible(entityId, null);
    }

    @Override
    public ManagedEntity assertMentionAccessible(Mention mention, Long requestedOwnerId) {
        User user = currentUser();
        boolean admin = ROLE_ADMIN.equals(user.getRole());
        if (requestedOwnerId != null && !admin) {
            // Reject (403) before inspecting the mention's links, mirroring assertAccessible.
            throw new AccessDeniedException("Only administrators may scope by ownerId");
        }

        // The owner each candidate link must belong to: the requested one for an admin scoped to a
        // user, the caller themselves for a non-admin, or null (any link) for an unscoped admin.
        Long requiredOwnerId = admin ? requestedOwnerId : user.getId();
        if (mention != null && mention.getManagedEntities() != null) {
            for (ManagedEntity entity : mention.getManagedEntities()) {
                if (requiredOwnerId == null
                        || (entity.getOwner() != null && requiredOwnerId.equals(entity.getOwner().getId()))) {
                    return entity;
                }
            }
        }
        // No link the caller may act through — same 404 whether absent or someone else's.
        throw new ResourceNotFoundException("Mention not found");
    }
}
