package com.aura.service.controller;

import com.aura.service.dto.MyLicenseResponse;
import com.aura.service.enums.LicenseTier;
import com.aura.service.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated user's own license: their tier and the limits it grants. This is a user-facing
 * endpoint, so it returns <strong>no price</strong> — the response is built from {@link LicenseTier}
 * limits only, and pricing is never read here.
 */
@RestController
@RequestMapping("/api/licenses/me")
@RequiredArgsConstructor
public class MyLicenseController {

    private final LicenseService licenseService;

    @GetMapping
    public ResponseEntity<MyLicenseResponse> myLicense() {
        // Report the effective tier (honoring any active offer-key override) so the limits the user
        // sees here match the ones enforcement applies.
        LicenseTier tier = licenseService.effectiveTier();
        MyLicenseResponse response = new MyLicenseResponse(
                tier,
                tier.getMaxKeywords(),
                tier.getMaxEntities(),
                tier.getMaxMentionsPerMonth(),
                tier.getCollectionFrequency().toString());
        return ResponseEntity.ok(response);
    }
}
