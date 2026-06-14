package com.aura.service.controller;

import com.aura.service.dto.CreateOfferKeyRequest;
import com.aura.service.dto.OfferKeyResponse;
import com.aura.service.dto.UpdateOfferKeyRequest;
import com.aura.service.entity.OfferKey;
import com.aura.service.service.OfferKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin-only CRUD for offer keys. Access is enforced both in {@code SecurityConfig}
 * ({@code /api/admin/**} requires {@code ROLE_ADMIN}) and by {@link PreAuthorize}. Responses carry
 * <strong>no price</strong> — an offer key grants access, never a purchase.
 */
@RestController
@RequestMapping("/api/admin/offer-keys")
@RequiredArgsConstructor
public class AdminOfferKeyController {

    private final OfferKeyService offerKeyService;

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<OfferKeyResponse> create(@Valid @RequestBody CreateOfferKeyRequest request) {
        return ResponseEntity.ok(toResponse(offerKeyService.create(request)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<OfferKeyResponse>> list() {
        List<OfferKeyResponse> keys = offerKeyService.list().stream()
                .map(AdminOfferKeyController::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(keys);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<OfferKeyResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(toResponse(offerKeyService.get(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<OfferKeyResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOfferKeyRequest request) {
        return ResponseEntity.ok(toResponse(offerKeyService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        offerKeyService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static OfferKeyResponse toResponse(OfferKey key) {
        return new OfferKeyResponse(
                key.getId(),
                key.getCode(),
                key.getGrantsTier(),
                key.isActive(),
                key.getExpiresAt(),
                key.getMaxRedemptions(),
                key.getRedemptionCount());
    }
}
