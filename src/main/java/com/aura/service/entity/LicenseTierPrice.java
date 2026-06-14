package com.aura.service.entity;

import com.aura.service.enums.LicenseTier;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The price catalog entry for a {@link LicenseTier}. This is <strong>sensitive, admin-only</strong>
 * data: it must never be returned by any user-facing endpoint. The tier itself is the natural
 * primary key, so there is exactly one price row per tier.
 */
@Entity
@Table(name = "license_tier_prices")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LicenseTierPrice {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false)
    private LicenseTier tier;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private String currency;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
