package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EntityAccessServiceImpl implements EntityAccessService {

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
    public ManagedEntity assertOwnedByCurrentUser(Long entityId) {
        User user = currentUser();
        ManagedEntity entity = entityId == null ? null
                : entityRepository.findById(entityId).orElse(null);
        if (entity == null || entity.getOwner() == null
                || !user.getId().equals(entity.getOwner().getId())) {
            // Same 404 whether the entity is absent or simply someone else's — never leak existence.
            throw new ResourceNotFoundException("Entity not found with id: " + entityId);
        }
        return entity;
    }
}
