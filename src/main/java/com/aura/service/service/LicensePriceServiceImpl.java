package com.aura.service.service;

import com.aura.service.dto.UpdateLicenseTierPriceRequest;
import com.aura.service.entity.LicenseTierPrice;
import com.aura.service.enums.LicenseTier;
import com.aura.service.repository.LicenseTierPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LicensePriceServiceImpl implements LicensePriceService {

    private static final String DEFAULT_CURRENCY = "USD";

    private final LicenseTierPriceRepository priceRepository;
    private final EntityAccessService entityAccessService;

    @Override
    @Transactional
    public void seedDefaults() {
        for (LicenseTier tier : LicenseTier.values()) {
            if (!priceRepository.existsById(tier)) {
                priceRepository.save(new LicenseTierPrice(
                        tier, BigDecimal.ZERO, DEFAULT_CURRENCY, Instant.now()));
            }
        }
    }

    @Override
    public List<LicenseTierPrice> listPrices() {
        requireAdmin();
        return priceRepository.findAll();
    }

    @Override
    @Transactional
    public List<LicenseTierPrice> updatePrices(List<UpdateLicenseTierPriceRequest> updates) {
        requireAdmin();
        for (UpdateLicenseTierPriceRequest update : updates) {
            LicenseTierPrice price = priceRepository.findById(update.getTier())
                    .orElseGet(() -> {
                        LicenseTierPrice created = new LicenseTierPrice();
                        created.setTier(update.getTier());
                        created.setCurrency(DEFAULT_CURRENCY);
                        return created;
                    });
            price.setPrice(update.getPrice());
            if (update.getCurrency() != null) {
                price.setCurrency(update.getCurrency());
            }
            price.setUpdatedAt(Instant.now());
            priceRepository.save(price);
        }
        return priceRepository.findAll();
    }

    /**
     * Hard gate: price data is admin-only. Even though {@code /api/admin/**} and the controller
     * {@code @PreAuthorize} already restrict access, we re-check here so price data can never leak
     * through some future caller that forgets the annotation.
     */
    private void requireAdmin() {
        if (!entityAccessService.currentUserIsAdmin()) {
            throw new AccessDeniedException("License prices are restricted to administrators");
        }
    }
}
