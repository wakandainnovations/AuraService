package com.aura.service.controller;

import com.aura.service.dto.RedeemOfferRequest;
import com.aura.service.dto.RedeemOfferResponse;
import com.aura.service.entity.License;
import com.aura.service.service.LicenseService;
import com.aura.service.service.OfferKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the authenticated user redeem an offer key to unlock a temporary Diamond-level override on
 * their existing license. An invalid/inactive/expired/exhausted key is rejected with a structured
 * {@code 400 { reason, message }} (see {@code GlobalExceptionHandler}). Like every user-facing license
 * endpoint, the response carries <strong>no price</strong>.
 */
@RestController
@RequestMapping("/api/license/redeem-offer")
@RequiredArgsConstructor
public class RedeemOfferController {

    private final OfferKeyService offerKeyService;
    private final LicenseService licenseService;

    @PostMapping
    public ResponseEntity<RedeemOfferResponse> redeem(@Valid @RequestBody RedeemOfferRequest request) {
        License license = offerKeyService.redeem(request.getCode());
        RedeemOfferResponse response = new RedeemOfferResponse(
                license.getTier(),
                license.getOverrideTier(),
                licenseService.effectiveTier(),
                license.getOverrideExpiresAt());
        return ResponseEntity.ok(response);
    }
}
