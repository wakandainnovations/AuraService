package com.aura.service.dto;

/**
 * Minimal user projection for the admin user-selector dropdown. Deliberately carries only the
 * id and username — never the password hash, role, or any other account detail.
 */
public record AdminUserSummary(Long id, String username) {
}
