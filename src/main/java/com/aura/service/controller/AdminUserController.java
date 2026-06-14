package com.aura.service.controller;

import com.aura.service.dto.AdminUserSummary;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin-only directory of users, used to populate the UI's user-selector dropdown (admins can scope
 * an entity view to a specific user via the {@code ownerId} param). Access is enforced both in
 * {@code SecurityConfig} ({@code /api/admin/**} requires {@code ROLE_ADMIN}) and by {@link PreAuthorize}.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<AdminUserSummary>> listUsers() {
        List<AdminUserSummary> users = userRepository.findAll().stream()
                .map(u -> new AdminUserSummary(u.getId(), u.getUsername()))
                .sorted(Comparator.comparing(AdminUserSummary::username, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }
}
