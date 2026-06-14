package com.aura.service.controller;

import com.aura.service.enums.LicenseTier;
import com.aura.service.licensing.RequiresTier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stub for the Audience &amp; Content premium module, which does not exist yet. It exists now only so the
 * tier gate has a real endpoint to protect: the whole surface is {@code DIAMOND}-only via
 * {@link RequiresTier}. The internals are intentionally not modelled — replace the placeholder payload
 * with the real module when it is built.
 */
@RestController
@RequestMapping("/api/audience-content")
@RequiresTier(value = LicenseTier.DIAMOND, feature = "Audience & Content")
@Tag(name = "Audience & Content",
        description = "Premium audience & content module (stub) — DIAMOND-tier only")
public class AudienceContentController {

    @Operation(summary = "Placeholder for the Audience & Content module (not yet implemented)")
    @GetMapping
    public ResponseEntity<Map<String, String>> placeholder() {
        return ResponseEntity.ok(Map.of("module", "audience-content", "status", "not-implemented"));
    }
}
