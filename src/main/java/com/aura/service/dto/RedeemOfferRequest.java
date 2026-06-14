package com.aura.service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user's request to redeem an offer key, identified by its {@code code}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedeemOfferRequest {

    @NotBlank(message = "code is required")
    private String code;
}
