package com.aura.service.controller;

import com.aura.service.dto.CreateTrackedReleaseRequest;
import com.aura.service.dto.TrackedReleaseResponse;
import com.aura.service.dto.UpdateTrackedReleaseRequest;
import com.aura.service.service.TrackedReleaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin review of the movies the social-media collector scans. Every India-origin TMDB release is
 * added automatically (by AuraSocialMediaService) and tracked from 7 days before to 7 days after
 * release; admins exclude ones not worth the collection budget, fix keywords, or add releases TMDB
 * doesn't list. Changes take effect on the collector's next per-entity check (no restart).
 */
@RestController
@RequestMapping("/api/admin/tracked-releases")
@RequiredArgsConstructor
public class AdminTrackedReleaseController {

    private final TrackedReleaseService trackedReleaseService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<TrackedReleaseResponse>> list(@RequestParam(defaultValue = "current") String scope) {
        return ResponseEntity.ok(trackedReleaseService.list(scope));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<TrackedReleaseResponse> create(@Valid @RequestBody CreateTrackedReleaseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trackedReleaseService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<TrackedReleaseResponse> update(@PathVariable Long id,
                                                         @RequestBody UpdateTrackedReleaseRequest request) {
        return ResponseEntity.ok(trackedReleaseService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        trackedReleaseService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
