package com.aura.service.service;

import com.aura.service.entity.License;
import com.aura.service.entity.User;
import com.aura.service.enums.LicenseTier;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.LicenseRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LicenseServiceImpl implements LicenseService {

    private static final String KEY_PREFIX = "AURA-";

    private final LicenseRepository licenseRepository;
    private final UserRepository userRepository;
    private final EntityAccessService entityAccessService;

    @Override
    public License resolveCurrentLicense() {
        User user = entityAccessService.currentUser();
        return licenseRepository.findByUserAndActiveTrue(user)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active license for user: " + user.getUsername()));
    }

    @Override
    public LicenseTier currentTier() {
        return resolveCurrentLicense().getTier();
    }

    @Override
    public LicenseTier effectiveTier() {
        return effectiveTierOf(resolveCurrentLicense(), Instant.now());
    }

    /**
     * The tier that actually governs {@code license}: its {@code overrideTier} when one is set and not
     * past {@code overrideExpiresAt} at {@code now}, otherwise the base {@code tier}. A null override
     * expiry means the override never lapses on its own.
     */
    static LicenseTier effectiveTierOf(License license, Instant now) {
        LicenseTier override = license.getOverrideTier();
        if (override != null) {
            Instant expiry = license.getOverrideExpiresAt();
            if (expiry == null || expiry.isAfter(now)) {
                return override;
            }
        }
        return license.getTier();
    }

    @Override
    public int currentMaxKeywords() {
        return effectiveTier().getMaxKeywords();
    }

    @Override
    public int currentMaxEntities() {
        return effectiveTier().getMaxEntities();
    }

    @Override
    public int currentMaxMentionsPerMonth() {
        return effectiveTier().getMaxMentionsPerMonth();
    }

    @Override
    public Duration currentCollectionFrequency() {
        return effectiveTier().getCollectionFrequency();
    }

    @Override
    @Transactional
    public License issueLicense(Long userId, LicenseTier tier, Instant expiresAt) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        // A user operates under a single active license — retire any existing ones before issuing.
        for (License existing : licenseRepository.findByUser(user)) {
            if (existing.isActive()) {
                existing.setActive(false);
                licenseRepository.save(existing);
            }
        }

        License license = new License();
        license.setLicenseKey(generateKey());
        license.setTier(tier);
        license.setUser(user);
        license.setActive(true);
        license.setIssuedAt(Instant.now());
        license.setExpiresAt(expiresAt);
        return licenseRepository.save(license);
    }

    @Override
    public List<License> listLicenses() {
        return licenseRepository.findAll();
    }

    @Override
    @Transactional
    public License updateLicense(Long licenseId, LicenseTier tier, Boolean active) {
        License license = licenseRepository.findById(licenseId)
                .orElseThrow(() -> new ResourceNotFoundException("License not found with id: " + licenseId));
        if (tier != null) {
            license.setTier(tier);
        }
        if (active != null) {
            license.setActive(active);
        }
        return licenseRepository.save(license);
    }

    private String generateKey() {
        return KEY_PREFIX + UUID.randomUUID();
    }
}
