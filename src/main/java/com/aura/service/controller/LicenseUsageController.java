package com.aura.service.controller;

import com.aura.service.dto.LicenseUsageResponse;
import com.aura.service.service.EntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only usage meter for the authenticated user: how many entities and keywords they are using
 * against their tier's caps. User-facing, so the response carries <strong>no price</strong> — only
 * counts and limits (see {@link LicenseUsageResponse}).
 */
@RestController
@RequestMapping("/api/license/usage")
@RequiredArgsConstructor
public class LicenseUsageController {

    private final EntityService entityService;

    @GetMapping
    public ResponseEntity<LicenseUsageResponse> usage() {
        return ResponseEntity.ok(entityService.currentUsage());
    }
}
