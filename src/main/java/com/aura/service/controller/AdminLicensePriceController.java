package com.aura.service.controller;

import com.aura.service.dto.LicenseTierPriceResponse;
import com.aura.service.dto.UpdateLicenseTierPriceRequest;
import com.aura.service.entity.LicenseTierPrice;
import com.aura.service.service.LicensePriceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The admin-only price catalog for license tiers — the <strong>only</strong> place in the API where
 * price data is exposed. Access is enforced in three layers: {@code SecurityConfig}
 * ({@code /api/admin/**} → {@code ROLE_ADMIN}), {@link PreAuthorize} here, and an admin re-check
 * inside {@link LicensePriceService}. No user-facing endpoint ever returns these payloads.
 */
@RestController
@RequestMapping("/api/admin/license-prices")
@RequiredArgsConstructor
public class AdminLicensePriceController {

    private final LicensePriceService licensePriceService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<LicenseTierPriceResponse>> listPrices() {
        return ResponseEntity.ok(toResponses(licensePriceService.listPrices()));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<LicenseTierPriceResponse>> updatePrices(
            @Valid @RequestBody List<UpdateLicenseTierPriceRequest> request) {
        return ResponseEntity.ok(toResponses(licensePriceService.updatePrices(request)));
    }

    private static List<LicenseTierPriceResponse> toResponses(List<LicenseTierPrice> prices) {
        return prices.stream()
                .map(p -> new LicenseTierPriceResponse(
                        p.getTier(), p.getPrice(), p.getCurrency(), p.getUpdatedAt()))
                .collect(Collectors.toList());
    }
}
