package com.aura.service.controller;

import com.aura.service.dto.WorkspaceExportBundle;
import com.aura.service.dto.WorkspaceImpactResponse;
import com.aura.service.dto.WorkspaceImportResult;
import com.aura.service.entity.User;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.WorkspaceImpactService;
import com.aura.service.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backup/restore for the authenticated user's workspace.
 * <p>
 * Export and import speak a single, proprietary JSON document ({@link WorkspaceExportBundle}).
 * There is intentionally no per-resource CSV (or other portable) export: backing up and
 * restoring within Aura is one click, while extracting individual resources for migration to a
 * competing tool is not supported.
 * <p>
 * <strong>FLAGGED FOR PRODUCT REVIEW:</strong> the JSON-only shape is a deliberate
 * retention/lock-in decision ("easy backup, hard exit"), not a technical constraint. If
 * product/legal would rather offer interoperable per-resource exports, this is the place to
 * change it.
 */
@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceImpactService workspaceImpactService;
    private final UserRepository userRepository;

    /**
     * The authenticated user's accumulated investment, reflected back as counters plus
     * display-ready highlight sentences (e.g. "Your playbook library has handled 12 crises.").
     * Intended for the dashboard header and the morning digest so the value the user has built
     * up is visible rather than silent.
     */
    @GetMapping("/impact")
    public ResponseEntity<WorkspaceImpactResponse> impact(
            @AuthenticationPrincipal UserDetails principal
    ) {
        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new RuntimeException(
                        "Authenticated user not found: " + principal.getUsername()));
        return ResponseEntity.ok(workspaceImpactService.getImpact(user.getId()));
    }

    @GetMapping("/export")
    public ResponseEntity<WorkspaceExportBundle> export(
            @AuthenticationPrincipal UserDetails principal
    ) {
        WorkspaceExportBundle bundle = workspaceService.export(principal.getUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"aura-workspace.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(bundle);
    }

    @PostMapping("/import")
    public ResponseEntity<WorkspaceImportResult> importWorkspace(
            @RequestBody WorkspaceExportBundle bundle,
            @AuthenticationPrincipal UserDetails principal
    ) {
        return ResponseEntity.ok(
                workspaceService.importWorkspace(principal.getUsername(), bundle));
    }
}
