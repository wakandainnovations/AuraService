package com.aura.service.service;

import com.aura.service.dto.AlertRuleRequest;
import com.aura.service.dto.AlertRuleResponse;
import com.aura.service.entity.AlertRule;
import com.aura.service.entity.User;
import com.aura.service.repository.AlertRuleRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private final AlertRuleRepository alertRuleRepository;
    private final ManagedEntityRepository entityRepository;
    private final UserRepository userRepository;

    public List<AlertRuleResponse> list(String username) {
        Long userId = resolveUserId(username);
        return alertRuleRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<AlertRuleResponse> get(Long id, String username) {
        Long userId = resolveUserId(username);
        return alertRuleRepository.findByIdAndUserId(id, userId).map(this::toResponse);
    }

    @Transactional
    public AlertRuleResponse create(AlertRuleRequest request, String username) {
        Long userId = resolveUserId(username);
        validateEntity(request.getEntityId());

        AlertRule rule = AlertRule.builder()
                .userId(userId)
                .entityId(request.getEntityId())
                .kind(request.getKind())
                .threshold(request.getThreshold())
                .channels(request.getChannels() != null ? new ArrayList<>(request.getChannels()) : new ArrayList<>())
                .enabled(request.isEnabled())
                .build();

        return toResponse(alertRuleRepository.save(rule));
    }

    @Transactional
    public Optional<AlertRuleResponse> update(Long id, AlertRuleRequest request, String username) {
        Long userId = resolveUserId(username);
        return alertRuleRepository.findByIdAndUserId(id, userId).map(rule -> {
            validateEntity(request.getEntityId());
            rule.setEntityId(request.getEntityId());
            rule.setKind(request.getKind());
            rule.setThreshold(request.getThreshold());
            rule.setChannels(request.getChannels() != null ? new ArrayList<>(request.getChannels()) : new ArrayList<>());
            rule.setEnabled(request.isEnabled());
            return toResponse(alertRuleRepository.save(rule));
        });
    }

    @Transactional
    public boolean delete(Long id, String username) {
        Long userId = resolveUserId(username);
        return alertRuleRepository.findByIdAndUserId(id, userId).map(rule -> {
            alertRuleRepository.delete(rule);
            return true;
        }).orElse(false);
    }

    private void validateEntity(Long entityId) {
        if (entityId != null && !entityRepository.existsById(entityId)) {
            throw new IllegalArgumentException("Unknown entityId: " + entityId);
        }
    }

    private Long resolveUserId(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + username));
    }

    private AlertRuleResponse toResponse(AlertRule rule) {
        return new AlertRuleResponse(
                rule.getId(),
                rule.getUserId(),
                rule.getEntityId(),
                rule.getKind(),
                rule.getThreshold(),
                rule.getChannels(),
                rule.isEnabled()
        );
    }
}
