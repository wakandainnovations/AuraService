package com.aura.service.controller;

import com.aura.service.dto.AdminLicenseSummary;
import com.aura.service.dto.IssueLicenseRequest;
import com.aura.service.dto.LicenseKeyResponse;
import com.aura.service.dto.UpdateLicenseRequest;
import com.aura.service.entity.License;
import com.aura.service.entity.User;
import com.aura.service.service.LicenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin-only license management. Access is enforced both in {@code SecurityConfig}
 * ({@code /api/admin/**} requires {@code ROLE_ADMIN}) and by {@link PreAuthorize}. None of these
 * responses carry price data — pricing lives behind {@code /api/admin/license-prices}.
 */
@RestController
@RequestMapping("/api/admin/licenses")
@RequiredArgsConstructor
public class AdminLicenseController {

    private final LicenseService licenseService;

    /** Issue/assign a license to a user at a tier; returns the generated license key. */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<LicenseKeyResponse> issueLicense(@Valid @RequestBody IssueLicenseRequest request) {
        License license = licenseService.issueLicense(
                request.getUserId(), request.getTier(), request.getExpiresAt());
        return ResponseEntity.ok(new LicenseKeyResponse(license.getLicenseKey()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<AdminLicenseSummary>> listLicenses() {
        List<AdminLicenseSummary> licenses = licenseService.listLicenses().stream()
                .map(AdminLicenseController::toSummary)
                .collect(Collectors.toList());
        return ResponseEntity.ok(licenses);
    }

    /** Change a license's tier and/or active flag. */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<AdminLicenseSummary> updateLicense(
            @PathVariable Long id,
            @RequestBody UpdateLicenseRequest request) {
        License license = licenseService.updateLicense(id, request.getTier(), request.getActive());
        return ResponseEntity.ok(toSummary(license));
    }

    private static AdminLicenseSummary toSummary(License license) {
        User user = license.getUser();
        return new AdminLicenseSummary(
                license.getId(),
                license.getLicenseKey(),
                license.getTier(),
                user != null ? user.getId() : null,
                user != null ? user.getUsername() : null,
                license.isActive(),
                license.getIssuedAt(),
                license.getExpiresAt());
    }
}
