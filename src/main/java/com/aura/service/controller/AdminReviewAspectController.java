package com.aura.service.controller;

import com.aura.service.dto.ReviewAspectBackfillResponse;
import com.aura.service.service.ReviewAspectBreakdownService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only trigger for {@link ReviewAspectBreakdownService}'s one-off backlog catch-up: unlike the
 * regular {@code refresh=true} path (capped per call) or the 2-hourly global sweep (capped and shared
 * across every movie), this drains one entity's entire not-yet-classified backlog. Starts the drain in
 * the background and returns immediately (202) rather than holding the request open for however long a
 * large backlog takes to classify — a movie needing hundreds of LLM calls would otherwise leave the
 * caller's HTTP client waiting for many minutes. Gated to admins both here and via
 * {@code SecurityConfig}'s {@code /api/admin/**} rule, since triggering it can still queue a large
 * number of LLM requests even though the caller no longer waits on them.
 */
@RestController
@RequestMapping("/api/admin/entities")
@RequiredArgsConstructor
public class AdminReviewAspectController {

    private final ReviewAspectBreakdownService reviewAspectBreakdownService;

    @PostMapping("/{entityId}/review-aspect-backfill")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ReviewAspectBackfillResponse> backfill(@PathVariable Long entityId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(reviewAspectBreakdownService.triggerBackfill(entityId));
    }
}
