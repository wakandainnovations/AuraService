package com.aura.service.service;

import com.aura.service.dto.EntitledResponse;
import com.aura.service.dto.FeatureCatalogEntry;
import com.aura.service.enums.LicenseTier;
import com.aura.service.licensing.Feature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {

    private final LicenseService licenseService;
    private final EntityAccessService entityAccessService;
    private final PreviewMaskingService previewMaskingService;

    @Override
    public boolean isEntitled(LicenseTier requiredTier) {
        // Consult the admin flag first: admins reach every premium feature regardless of any license,
        // and short-circuiting here means a user without a license still resolves (effectiveTier() would
        // otherwise 404 for them).
        if (entityAccessService.currentUserIsAdmin()) {
            return true;
        }
        return licenseService.effectiveTier().isAtLeast(requiredTier);
    }

    @Override
    public <T> EntitledResponse<T> evaluate(Feature feature, Supplier<T> realPayload) {
        return wrap(feature, realPayload.get());
    }

    @Override
    public <T> EntitledResponse<T> wrap(Feature feature, T payload) {
        if (isEntitled(feature.getRequiredTier())) {
            return EntitledResponse.entitled(feature.getRequiredTier(), payload);
        }
        return EntitledResponse.locked(feature.getRequiredTier(), previewMaskingService.mask(payload));
    }

    @Override
    public <T> EntitledResponse<T> gate(Feature feature, Supplier<T> action) {
        if (isEntitled(feature.getRequiredTier())) {
            return EntitledResponse.entitled(feature.getRequiredTier(), action.get());
        }
        // No real payload is produced for a blocked mutation, so there is nothing to mask.
        return EntitledResponse.locked(feature.getRequiredTier(), null);
    }

    @Override
    public List<FeatureCatalogEntry> catalog() {
        // Resolve the entitlement inputs once, then map every feature against them.
        boolean admin = entityAccessService.currentUserIsAdmin();
        LicenseTier effective = admin ? null : licenseService.effectiveTier();
        List<FeatureCatalogEntry> entries = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            boolean entitled = admin || effective.isAtLeast(feature.getRequiredTier());
            entries.add(new FeatureCatalogEntry(
                    feature.getKey(), feature.getDisplayName(), feature.getRequiredTier(), entitled));
        }
        return entries;
    }
}
