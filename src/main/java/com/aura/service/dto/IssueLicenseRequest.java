package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Admin request to issue/assign a license to a user at a given tier. {@code expiresAt} is optional —
 * omit it for a license that never expires.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IssueLicenseRequest {

    @NotNull(message = "userId is required")
    private Long userId;

    @NotNull(message = "tier is required")
    private LicenseTier tier;

    private Instant expiresAt;
}
