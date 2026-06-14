package com.aura.service.entity;

import com.aura.service.enums.LicenseTier;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A license key issued to a {@link User}. Every user uses the product through exactly one active
 * license; the {@link #tier} fixes the per-tier limits (see {@link LicenseTier}). Prices are not
 * stored here — they live, admin-only, in {@link LicenseTierPrice}.
 */
@Entity
@Table(name = "licenses")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class License {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "license_key", unique = true, nullable = false)
    private String licenseKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LicenseTier tier;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    // Null means the license never expires.
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Temporary tier override granted by redeeming an offer key (see {@code OfferKey}). When set and
     * not past {@link #overrideExpiresAt}, the user's <em>effective</em> tier is this value instead of
     * {@link #tier} — raising both the feature gates and the numeric limits to the granted tier. Null
     * means no override is in effect and the base {@link #tier} governs.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "override_tier")
    private LicenseTier overrideTier;

    /**
     * When the {@link #overrideTier} stops applying; null means it never expires on its own. Once this
     * instant has passed, the effective tier falls back to the base {@link #tier}.
     */
    @Column(name = "override_expires_at")
    private Instant overrideExpiresAt;
}
