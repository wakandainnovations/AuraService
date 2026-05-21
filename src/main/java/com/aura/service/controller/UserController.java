package com.aura.service.controller;

import com.aura.service.dto.UpdateWebhookRequest;
import com.aura.service.entity.User;
import com.aura.service.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @PutMapping("/me/webhook")
    @Transactional
    public ResponseEntity<Map<String, String>> updateWebhook(
            @Valid @RequestBody UpdateWebhookRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        return userRepository.findByUsername(principal.getUsername())
                .map(user -> {
                    String normalized = request.getWebhookUrl();
                    if (normalized != null && normalized.isBlank()) {
                        normalized = null;
                    }
                    user.setAlertWebhookUrl(normalized);
                    User saved = userRepository.save(user);
                    Map<String, String> body = new java.util.LinkedHashMap<>();
                    body.put("username", saved.getUsername());
                    body.put("alertWebhookUrl", saved.getAlertWebhookUrl());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
