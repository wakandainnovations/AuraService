package com.aura.service.service;

import com.aura.service.dto.UpdateLicenseTierPriceRequest;
import com.aura.service.entity.LicenseTierPrice;
import com.aura.service.enums.LicenseTier;
import com.aura.service.repository.LicenseTierPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for the admin-only price catalog. Crucially: the read and update operations must reject
 * non-admins (prices are sensitive and may never reach a regular user). Collaborators are mocked as
 * interfaces ({@link LicenseTierPriceRepository}, {@link EntityAccessService}), never concrete classes.
 */
class LicensePriceServiceImplTest {

    private LicenseTierPriceRepository priceRepository;
    private EntityAccessService entityAccessService;
    private LicensePriceServiceImpl service;

    @BeforeEach
    void setUp() {
        priceRepository = mock(LicenseTierPriceRepository.class);
        entityAccessService = mock(EntityAccessService.class);
        service = new LicensePriceServiceImpl(priceRepository, entityAccessService);
    }

    // ------------------------------------------------------------------
    // Non-admins are rejected (the critical price-exposure guard).
    // ------------------------------------------------------------------

    @Test
    void listPrices_nonAdmin_isForbidden_andNeverTouchesTheCatalog() {
        when(entityAccessService.currentUserIsAdmin()).thenReturn(false);

        assertThatThrownBy(() -> service.listPrices())
                .isInstanceOf(AccessDeniedException.class);

        // Rejected before any price is ever read — no leak path.
        verify(priceRepository, never()).findAll();
    }

    @Test
    void updatePrices_nonAdmin_isForbidden_andNeverWrites() {
        when(entityAccessService.currentUserIsAdmin()).thenReturn(false);

        List<UpdateLicenseTierPriceRequest> updates = List.of(
                new UpdateLicenseTierPriceRequest(LicenseTier.GOLD, new BigDecimal("99.00"), "USD"));

        assertThatThrownBy(() -> service.updatePrices(updates))
                .isInstanceOf(AccessDeniedException.class);

        verify(priceRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Admins may read and update.
    // ------------------------------------------------------------------

    @Test
    void listPrices_admin_returnsCatalog() {
        when(entityAccessService.currentUserIsAdmin()).thenReturn(true);
        LicenseTierPrice gold = new LicenseTierPrice(
                LicenseTier.GOLD, new BigDecimal("49.00"), "USD", null);
        when(priceRepository.findAll()).thenReturn(List.of(gold));

        assertThat(service.listPrices()).containsExactly(gold);
    }

    @Test
    void updatePrices_admin_upsertsTierPrice() {
        when(entityAccessService.currentUserIsAdmin()).thenReturn(true);
        LicenseTierPrice existing = new LicenseTierPrice(
                LicenseTier.GOLD, BigDecimal.ZERO, "USD", null);
        when(priceRepository.findById(LicenseTier.GOLD)).thenReturn(Optional.of(existing));
        when(priceRepository.findAll()).thenReturn(List.of(existing));

        service.updatePrices(List.of(
                new UpdateLicenseTierPriceRequest(LicenseTier.GOLD, new BigDecimal("49.00"), "EUR")));

        ArgumentCaptor<LicenseTierPrice> captor = ArgumentCaptor.forClass(LicenseTierPrice.class);
        verify(priceRepository).save(captor.capture());
        assertThat(captor.getValue().getPrice()).isEqualByComparingTo("49.00");
        assertThat(captor.getValue().getCurrency()).isEqualTo("EUR");
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Startup seeding (not admin-guarded — runs without a caller).
    // ------------------------------------------------------------------

    @Test
    void seedDefaults_insertsMissingTiersAtZero_andSkipsExisting() {
        // GOLD already present; the other three are missing.
        when(priceRepository.existsById(any(LicenseTier.class))).thenReturn(false);
        when(priceRepository.existsById(LicenseTier.GOLD)).thenReturn(true);

        service.seedDefaults();

        ArgumentCaptor<LicenseTierPrice> captor = ArgumentCaptor.forClass(LicenseTierPrice.class);
        verify(priceRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(p -> assertThat(p.getPrice()).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(captor.getAllValues()).extracting(LicenseTierPrice::getTier)
                .doesNotContain(LicenseTier.GOLD);
    }
}
