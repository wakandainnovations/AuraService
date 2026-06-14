package com.aura.service.dto;

import com.aura.service.enums.LicenseTier;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One entry of the admin-only price-catalog update. {@code currency} is optional; when omitted the
 * existing currency on the row is preserved.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateLicenseTierPriceRequest {

    @NotNull(message = "tier is required")
    private LicenseTier tier;

    @NotNull(message = "price is required")
    private BigDecimal price;

    private String currency;
}
