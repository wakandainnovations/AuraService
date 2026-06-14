package com.aura.service.service;

import com.aura.service.dto.CreateOfferKeyRequest;
import com.aura.service.dto.UpdateOfferKeyRequest;
import com.aura.service.entity.License;
import com.aura.service.entity.OfferKey;
import com.aura.service.enums.LicenseTier;
import com.aura.service.exception.OfferKeyRedemptionException;
import com.aura.service.exception.OfferKeyRedemptionException.Reason;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.LicenseRepository;
import com.aura.service.repository.OfferKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OfferKeyServiceImpl implements OfferKeyService {

    private final OfferKeyRepository offerKeyRepository;
    private final LicenseService licenseService;
    private final LicenseRepository licenseRepository;

    @Override
    @Transactional
    public OfferKey create(CreateOfferKeyRequest request) {
        if (offerKeyRepository.existsByCode(request.getCode())) {
            // Surfaced as a 400 by the generic RuntimeException handler.
            throw new IllegalArgumentException("Offer key already exists with code: " + request.getCode());
        }
        OfferKey key = new OfferKey();
        key.setCode(request.getCode());
        key.setGrantsTier(request.getGrantsTier() != null ? request.getGrantsTier() : LicenseTier.DIAMOND);
        key.setActive(request.getActive() == null || request.getActive());
        key.setExpiresAt(request.getExpiresAt());
        key.setMaxRedemptions(request.getMaxRedemptions());
        key.setRedemptionCount(0);
        return offerKeyRepository.save(key);
    }

    @Override
    public List<OfferKey> list() {
        return offerKeyRepository.findAll();
    }

    @Override
    public OfferKey get(Long id) {
        return offerKeyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Offer key not found with id: " + id));
    }

    @Override
    @Transactional
    public OfferKey update(Long id, UpdateOfferKeyRequest request) {
        OfferKey key = get(id);
        if (request.getGrantsTier() != null) {
            key.setGrantsTier(request.getGrantsTier());
        }
        if (request.getActive() != null) {
            key.setActive(request.getActive());
        }
        if (request.getExpiresAt() != null) {
            key.setExpiresAt(request.getExpiresAt());
        }
        if (request.getMaxRedemptions() != null) {
            key.setMaxRedemptions(request.getMaxRedemptions());
        }
        return offerKeyRepository.save(key);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        OfferKey key = get(id);
        offerKeyRepository.delete(key);
    }

    @Override
    @Transactional
    public License redeem(String code) {
        Instant now = Instant.now();
        OfferKey key = offerKeyRepository.findByCode(code)
                .orElseThrow(() -> new OfferKeyRedemptionException(
                        Reason.INVALID, "No offer key matches that code"));

        if (!key.isActive()) {
            throw new OfferKeyRedemptionException(Reason.INACTIVE, "This offer key is no longer active");
        }
        if (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now)) {
            throw new OfferKeyRedemptionException(Reason.EXPIRED, "This offer key has expired");
        }
        if (key.getMaxRedemptions() != null && key.getRedemptionCount() >= key.getMaxRedemptions()) {
            throw new OfferKeyRedemptionException(
                    Reason.EXHAUSTED, "This offer key has reached its redemption limit");
        }

        // Apply the override to the caller's active license. The granted override inherits the key's
        // own expiry, so the elevated access ends exactly when the key would have lapsed.
        License license = licenseService.resolveCurrentLicense();
        license.setOverrideTier(key.getGrantsTier());
        license.setOverrideExpiresAt(key.getExpiresAt());
        licenseRepository.save(license);

        key.setRedemptionCount(key.getRedemptionCount() + 1);
        offerKeyRepository.save(key);
        return license;
    }
}
