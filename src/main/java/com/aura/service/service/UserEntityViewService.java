package com.aura.service.service;

import com.aura.service.entity.User;
import com.aura.service.entity.UserEntityView;
import com.aura.service.repository.UserEntityViewRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserEntityViewService {

    private final UserEntityViewRepository viewRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public void recordView(String username, Long entityId) {
        if (username == null || entityId == null) {
            return;
        }
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return;
        }
        Long userId = userOpt.get().getId();
        Instant now = Instant.now(clock);

        int updated = viewRepository.touchLastSeen(userId, entityId, now);
        if (updated > 0) {
            return;
        }
        try {
            UserEntityView view = new UserEntityView();
            view.setUserId(userId);
            view.setEntityId(entityId);
            view.setLastSeenAt(now);
            viewRepository.save(view);
        } catch (DataIntegrityViolationException race) {
            viewRepository.touchLastSeen(userId, entityId, now);
        }
    }

    public Optional<Instant> findLastSeen(Long userId, Long entityId) {
        return viewRepository.findLastSeen(userId, entityId);
    }

    public Optional<Instant> findLastSeen(String username, Long entityId) {
        if (username == null || entityId == null) {
            return Optional.empty();
        }
        return userRepository.findByUsername(username)
                .flatMap(u -> viewRepository.findLastSeen(u.getId(), entityId));
    }
}
