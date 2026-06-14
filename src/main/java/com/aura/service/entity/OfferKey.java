package com.aura.service.entity;

import com.aura.service.enums.LicenseTier;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A redeemable code that grants a temporary tier <em>override</em> on top of whatever license a user
 * has purchased. Redeeming a key sets {@code License.overrideTier}/{@code overrideExpiresAt} so the
 * holder's <em>effective</em> tier becomes {@link #grantsTier} (Diamond by default) until the override
 * expires — raising both the premium-feature gates and the numeric limits to that tier.
 *
 * <p>Offer keys are managed admin-only (CRUD under {@code /api/admin/offer-keys}) and carry
 * <strong>no price</strong> — they are an access-granting mechanism, not a billing one.
 */
@Entity
@Table(name = "offer_keys")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The code a user types to redeem the offer; unique across all keys. */
    @Column(name = "code", unique = true, nullable = false)
    private String code;

    /** The tier this key grants while active. Defaults to the top tier. */
    @Enumerated(EnumType.STRING)
    @Column(name = "grants_tier", nullable = false)
    private LicenseTier grantsTier = LicenseTier.DIAMOND;

    /** When false, the key cannot be redeemed regardless of expiry/redemptions. */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * When the key stops being redeemable; null means it never expires. A redeemed override inherits
     * this instant as its own expiry, so the granted access ends when the key would have lapsed.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Maximum number of times the key may be redeemed; null means unlimited. */
    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    /** How many times the key has been redeemed so far. */
    @Column(name = "redemption_count", nullable = false)
    private int redemptionCount = 0;
}
