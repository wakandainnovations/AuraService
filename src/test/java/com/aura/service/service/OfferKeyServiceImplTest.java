package com.aura.service.service;

import com.aura.service.dto.CreateOfferKeyRequest;
import com.aura.service.entity.License;
import com.aura.service.entity.OfferKey;
import com.aura.service.entity.User;
import com.aura.service.enums.LicenseTier;
import com.aura.service.exception.OfferKeyRedemptionException;
import com.aura.service.exception.OfferKeyRedemptionException.Reason;
import com.aura.service.repository.LicenseRepository;
import com.aura.service.repository.OfferKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for offer-key redemption. Every collaborator is mocked as an interface
 * ({@link OfferKeyRepository}, {@link LicenseService}, {@link LicenseRepository}) — never a concrete
 * class — so the redemption rules are exercised in isolation.
 */
class OfferKeyServiceImplTest {

    private static final String CODE = "DIAMOND-2026";

    private OfferKeyRepository offerKeyRepository;
    private LicenseService licenseService;
    private LicenseRepository licenseRepository;
    private OfferKeyServiceImpl service;

    @BeforeEach
    void setUp() {
        offerKeyRepository = mock(OfferKeyRepository.class);
        licenseService = mock(LicenseService.class);
        licenseRepository = mock(LicenseRepository.class);
        service = new OfferKeyServiceImpl(offerKeyRepository, licenseService, licenseRepository);
    }

    private OfferKey key(LicenseTier grants, boolean active, Instant expiresAt,
                         Integer maxRedemptions, int redemptionCount) {
        OfferKey k = new OfferKey();
        k.setId(1L);
        k.setCode(CODE);
        k.setGrantsTier(grants);
        k.setActive(active);
        k.setExpiresAt(expiresAt);
        k.setMaxRedemptions(maxRedemptions);
        k.setRedemptionCount(redemptionCount);
        return k;
    }

    private License bronzeLicense() {
        License license = new License();
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        license.setUser(user);
        license.setTier(LicenseTier.BRONZE);
        license.setActive(true);
        return license;
    }

    // ------------------------------------------------------------------
    // Successful redemption.
    // ------------------------------------------------------------------

    @Test
    void redeem_validKey_grantsDiamondOverride_incrementsCount_andPersists() {
        Instant expiry = Instant.now().plus(Duration.ofDays(30));
        OfferKey key = key(LicenseTier.DIAMOND, true, expiry, 100, 4);
        License license = bronzeLicense();

        when(offerKeyRepository.findByCode(CODE)).thenReturn(Optional.of(key));
        when(licenseService.resolveCurrentLicense()).thenReturn(license);

        License result = service.redeem(CODE);

        // A Bronze user's license now carries a Diamond override that lapses with the key.
        assertThat(result.getTier()).isEqualTo(LicenseTier.BRONZE);
        assertThat(result.getOverrideTier()).isEqualTo(LicenseTier.DIAMOND);
        assertThat(result.getOverrideExpiresAt()).isEqualTo(expiry);
        // The redemption is counted and both records are saved.
        assertThat(key.getRedemptionCount()).isEqualTo(5);
        verify(licenseRepository).save(license);
        verify(offerKeyRepository).save(key);
    }

    @Test
    void redeem_keyWithoutExpiryOrCap_grantsNonExpiringOverride() {
        OfferKey key = key(LicenseTier.DIAMOND, true, null, null, 0);
        License license = bronzeLicense();

        when(offerKeyRepository.findByCode(CODE)).thenReturn(Optional.of(key));
        when(licenseService.resolveCurrentLicense()).thenReturn(license);

        License result = service.redeem(CODE);

        assertThat(result.getOverrideTier()).isEqualTo(LicenseTier.DIAMOND);
        assertThat(result.getOverrideExpiresAt()).isNull();
        assertThat(key.getRedemptionCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Rejections — each leaves the license untouched (no save).
    // ------------------------------------------------------------------

    @Test
    void redeem_unknownCode_rejectedAsInvalid() {
        when(offerKeyRepository.findByCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeem(CODE))
                .isInstanceOf(OfferKeyRedemptionException.class)
                .satisfies(ex -> assertThat(((OfferKeyRedemptionException) ex).getReason())
                        .isEqualTo(Reason.INVALID));

        verifyNoOverrideApplied();
    }

    @Test
    void redeem_inactiveKey_rejected() {
        OfferKey key = key(LicenseTier.DIAMOND, false, null, null, 0);
        when(offerKeyRepository.findByCode(CODE)).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> service.redeem(CODE))
                .isInstanceOf(OfferKeyRedemptionException.class)
                .satisfies(ex -> assertThat(((OfferKeyRedemptionException) ex).getReason())
                        .isEqualTo(Reason.INACTIVE));

        verifyNoOverrideApplied();
    }

    @Test
    void redeem_expiredKey_rejected() {
        OfferKey key = key(LicenseTier.DIAMOND, true, Instant.now().minus(Duration.ofMinutes(1)), null, 0);
        when(offerKeyRepository.findByCode(CODE)).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> service.redeem(CODE))
                .isInstanceOf(OfferKeyRedemptionException.class)
                .satisfies(ex -> assertThat(((OfferKeyRedemptionException) ex).getReason())
                        .isEqualTo(Reason.EXPIRED));

        verifyNoOverrideApplied();
    }

    @Test
    void redeem_exhaustedKey_rejected() {
        // redemptionCount has already reached maxRedemptions.
        OfferKey key = key(LicenseTier.DIAMOND, true, null, 3, 3);
        when(offerKeyRepository.findByCode(CODE)).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> service.redeem(CODE))
                .isInstanceOf(OfferKeyRedemptionException.class)
                .satisfies(ex -> assertThat(((OfferKeyRedemptionException) ex).getReason())
                        .isEqualTo(Reason.EXHAUSTED));

        verifyNoOverrideApplied();
    }

    // ------------------------------------------------------------------
    // Admin create.
    // ------------------------------------------------------------------

    @Test
    void create_defaultsGrantsTierToDiamondAndActiveToTrue() {
        CreateOfferKeyRequest request = new CreateOfferKeyRequest();
        request.setCode(CODE);
        // grantsTier and active left null — defaults should apply.
        when(offerKeyRepository.existsByCode(CODE)).thenReturn(false);
        when(offerKeyRepository.save(any(OfferKey.class))).thenAnswer(inv -> inv.getArgument(0));

        OfferKey created = service.create(request);

        assertThat(created.getGrantsTier()).isEqualTo(LicenseTier.DIAMOND);
        assertThat(created.isActive()).isTrue();
        assertThat(created.getRedemptionCount()).isZero();
    }

    @Test
    void create_duplicateCode_rejected() {
        CreateOfferKeyRequest request = new CreateOfferKeyRequest();
        request.setCode(CODE);
        when(offerKeyRepository.existsByCode(CODE)).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void verifyNoOverrideApplied() {
        verify(licenseRepository, never()).save(any(License.class));
        verify(offerKeyRepository, never()).save(any(OfferKey.class));
    }
}
