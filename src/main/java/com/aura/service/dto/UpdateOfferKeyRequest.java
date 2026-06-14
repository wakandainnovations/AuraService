package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Admin partial-update for an offer key: change {@code grantsTier}, {@code active}, {@code expiresAt},
 * and/or {@code maxRedemptions}. A {@code null} field is left unchanged. The {@code code} and the
 * accumulated {@code redemptionCount} are immutable here.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateOfferKeyRequest {

    private LicenseTier grantsTier;

    private Boolean active;

    private Instant expiresAt;

    @Positive(message = "maxRedemptions must be positive")
    private Integer maxRedemptions;
}
