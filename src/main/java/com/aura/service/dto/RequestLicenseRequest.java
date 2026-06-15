package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user's self-service request for a new license, choosing the {@link LicenseTier} they want
 * (BRONZE, SILVER, GOLD or DIAMOND). The license is issued to the authenticated caller — there is no
 * {@code userId}, since a user can only request a license for themselves.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequestLicenseRequest {

    @NotNull(message = "tier is required")
    private LicenseTier tier;
}
