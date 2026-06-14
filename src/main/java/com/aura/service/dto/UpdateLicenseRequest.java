package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin partial-update for a license: change the {@code tier}, the {@code active} flag, or both.
 * A {@code null} field is left unchanged.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateLicenseRequest {

    private LicenseTier tier;

    private Boolean active;
}
