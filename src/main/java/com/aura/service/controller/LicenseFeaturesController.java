package com.aura.service.controller;

import com.aura.service.dto.FeatureCatalogEntry;
import com.aura.service.service.EntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Catalog of every premium feature with the current user's entitlement, so the UI can render all
 * features up-front and put a lock badge on the ones the user's tier hasn't unlocked yet.
 * Deliberately price-free: it names each feature's required tier, never its cost.
 */
@RestController
@RequestMapping("/api/license/features")
@RequiredArgsConstructor
@Tag(name = "License Features",
        description = "Full premium-feature catalog with per-user entitlement (for lock badges)")
public class LicenseFeaturesController {

    private final EntitlementService entitlementService;

    @Operation(summary = "List every premium feature with { key, name, requiredTier, entitled } for the current user")
    @GetMapping
    public ResponseEntity<List<FeatureCatalogEntry>> features() {
        return ResponseEntity.ok(entitlementService.catalog());
    }
}
