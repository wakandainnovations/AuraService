package com.aura.service.controller;

import com.aura.service.dto.EntitledResponse;
import com.aura.service.licensing.Feature;
import com.aura.service.service.EntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stub for the Audience &amp; Content premium module, which does not exist yet. It exists now only so the
 * tier gate has a real endpoint to surface: the whole surface is {@link Feature#AUDIENCE_CONTENT
 * DIAMOND}-only. Under-tier users get a {@code 200} with a masked preview rather than a {@code 403};
 * replace the placeholder payload with the real module when it is built.
 */
@RestController
@RequestMapping("/api/audience-content")
@RequiredArgsConstructor
@Tag(name = "Audience & Content",
        description = "Premium audience & content module (stub) — DIAMOND-tier only")
public class AudienceContentController {

    private final EntitlementService entitlementService;

    @Operation(summary = "Placeholder for the Audience & Content module (not yet implemented)")
    @GetMapping
    public EntitledResponse<Map<String, String>> placeholder() {
        return entitlementService.evaluate(Feature.AUDIENCE_CONTENT,
                () -> Map.of("module", "audience-content", "status", "not-implemented"));
    }
}
