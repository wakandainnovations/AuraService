package com.aura.service.service;

import com.aura.service.dto.EntitledResponse;
import com.aura.service.dto.FeatureCatalogEntry;
import com.aura.service.enums.LicenseTier;
import com.aura.service.licensing.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * The entitlement rule (admin OR effective tier high enough) and how it shapes the envelope. Only the
 * collaborator <em>interfaces</em> are mocked; the real {@link PreviewMaskingServiceImpl} is used.
 */
class EntitlementServiceTest {

    private LicenseService licenseService;
    private EntityAccessService entityAccess;
    private EntitlementService service;

    @BeforeEach
    void setUp() {
        licenseService = mock(LicenseService.class);
        entityAccess = mock(EntityAccessService.class);
        service = new EntitlementServiceImpl(licenseService, entityAccess, new PreviewMaskingServiceImpl());
    }

    private void asUser(LicenseTier tier) {
        when(entityAccess.currentUserIsAdmin()).thenReturn(false);
        when(licenseService.effectiveTier()).thenReturn(tier);
    }

    private void asAdmin() {
        when(entityAccess.currentUserIsAdmin()).thenReturn(true);
    }

    // ------------------------------------------------------------------
    // isEntitled
    // ------------------------------------------------------------------

    @Test
    void isEntitled_admin_alwaysTrue_withoutConsultingTier() {
        asAdmin();
        assertThat(service.isEntitled(LicenseTier.DIAMOND)).isTrue();
        verify(licenseService, never()).effectiveTier();
    }

    @Test
    void isEntitled_comparesEffectiveTier() {
        asUser(LicenseTier.GOLD);
        assertThat(service.isEntitled(LicenseTier.SILVER)).isTrue();   // above
        assertThat(service.isEntitled(LicenseTier.GOLD)).isTrue();     // equal
        assertThat(service.isEntitled(LicenseTier.DIAMOND)).isFalse(); // below
    }

    // ------------------------------------------------------------------
    // evaluate (read): always computes; masks when not entitled
    // ------------------------------------------------------------------

    @Test
    void evaluate_entitled_returnsRealDataNoPreview() {
        asUser(LicenseTier.SILVER);
        EntitledResponse<String> resp = service.evaluate(Feature.CHECKPOINTS, () -> "real-value");

        assertThat(resp.isEntitled()).isTrue();
        assertThat(resp.getRequiredTier()).isEqualTo(LicenseTier.SILVER);
        assertThat(resp.getData()).isEqualTo("real-value");
        assertThat(resp.getPreview()).isNull();
    }

    @Test
    void evaluate_unentitled_returnsMaskedPreviewAndNoData_butStillComputed() {
        asUser(LicenseTier.BRONZE); // below SILVER
        AtomicBoolean computed = new AtomicBoolean(false);

        EntitledResponse<String> resp = service.evaluate(Feature.CHECKPOINTS, () -> {
            computed.set(true);
            return "real-value";
        });

        assertThat(computed).isTrue(); // a read's payload is computed, then masked
        assertThat(resp.isEntitled()).isFalse();
        assertThat(resp.getRequiredTier()).isEqualTo(LicenseTier.SILVER);
        assertThat(resp.getData()).isNull();
        assertThat(resp.getPreview()).isEqualTo(PreviewMaskingServiceImpl.MASKED_TEXT); // string → starred
    }

    // ------------------------------------------------------------------
    // gate (write): runs the action only when entitled
    // ------------------------------------------------------------------

    @Test
    void gate_entitled_runsAction() {
        asUser(LicenseTier.SILVER);
        AtomicBoolean ran = new AtomicBoolean(false);

        EntitledResponse<String> resp = service.gate(Feature.CHECKPOINTS, () -> {
            ran.set(true);
            return "created";
        });

        assertThat(ran).isTrue();
        assertThat(resp.isEntitled()).isTrue();
        assertThat(resp.getData()).isEqualTo("created");
        assertThat(resp.getPreview()).isNull();
    }

    @Test
    void gate_unentitled_neverRunsAction_andHasNoPreview() {
        asUser(LicenseTier.BRONZE);
        AtomicBoolean ran = new AtomicBoolean(false);

        EntitledResponse<String> resp = service.gate(Feature.CHECKPOINTS, () -> {
            ran.set(true);
            return "created";
        });

        assertThat(ran).isFalse(); // the mutation must not run
        assertThat(resp.isEntitled()).isFalse();
        assertThat(resp.getData()).isNull();
        assertThat(resp.getPreview()).isNull();
    }

    // ------------------------------------------------------------------
    // catalog
    // ------------------------------------------------------------------

    @Test
    void catalog_reflectsUserTier() {
        asUser(LicenseTier.GOLD);
        List<FeatureCatalogEntry> catalog = service.catalog();

        assertThat(catalog).hasSize(Feature.values().length);
        assertThat(entitledKeys(catalog)).containsExactlyInAnyOrder("checkpoints", "crisis");
        // Every entry names its tier and key.
        assertThat(catalog).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("crisis");
            assertThat(e.name()).isEqualTo("Crisis Management");
            assertThat(e.requiredTier()).isEqualTo(LicenseTier.GOLD);
            assertThat(e.entitled()).isTrue();
        });
    }

    @Test
    void catalog_admin_entitledToEverything() {
        asAdmin();
        List<FeatureCatalogEntry> catalog = service.catalog();

        assertThat(catalog).allMatch(FeatureCatalogEntry::entitled);
        verify(licenseService, never()).effectiveTier();
    }

    private List<String> entitledKeys(List<FeatureCatalogEntry> catalog) {
        return catalog.stream().filter(FeatureCatalogEntry::entitled).map(FeatureCatalogEntry::key).toList();
    }
}
