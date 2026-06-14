package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Admin request to create an offer key. Only {@code code} is required: {@code grantsTier} defaults to
 * {@code DIAMOND} and {@code active} to {@code true} when omitted; {@code expiresAt} and
 * {@code maxRedemptions} are optional (null = never expires / unlimited redemptions).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOfferKeyRequest {

    @NotBlank(message = "code is required")
    private String code;

    /** Tier the key grants; defaults to {@code DIAMOND} when null. */
    private LicenseTier grantsTier;

    /** Whether the key is redeemable; defaults to {@code true} when null. */
    private Boolean active;

    private Instant expiresAt;

    @Positive(message = "maxRedemptions must be positive")
    private Integer maxRedemptions;
}
