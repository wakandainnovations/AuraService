package com.aura.service.service;

import com.aura.service.entity.License;
import com.aura.service.entity.User;
import com.aura.service.enums.LicenseTier;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.LicenseRepository;
import com.aura.service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for license assignment and resolution. All collaborators are mocked as interfaces
 * ({@link LicenseRepository}, {@link UserRepository}, {@link EntityAccessService}) — never concrete
 * classes.
 */
class LicenseServiceImplTest {

    private static final Long USER_ID = 7L;

    private LicenseRepository licenseRepository;
    private UserRepository userRepository;
    private EntityAccessService entityAccessService;
    private LicenseServiceImpl service;

    @BeforeEach
    void setUp() {
        licenseRepository = mock(LicenseRepository.class);
        userRepository = mock(UserRepository.class);
        entityAccessService = mock(EntityAccessService.class);
        service = new LicenseServiceImpl(licenseRepository, userRepository, entityAccessService);
    }

    private User user(Long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("x");
        u.setRole("ROLE_USER");
        return u;
    }

    @Test
    void issueLicense_createsActiveLicenseWithGeneratedKey_andReturnsIt() {
        User target = user(USER_ID, "alice");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(target));
        when(licenseRepository.findByUser(target)).thenReturn(List.of());
        when(licenseRepository.save(any(License.class))).thenAnswer(inv -> inv.getArgument(0));

        License result = service.issueLicense(USER_ID, LicenseTier.GOLD, null);

        assertThat(result.getTier()).isEqualTo(LicenseTier.GOLD);
        assertThat(result.getUser()).isEqualTo(target);
        assertThat(result.isActive()).isTrue();
        assertThat(result.getIssuedAt()).isNotNull();
        assertThat(result.getExpiresAt()).isNull();
        assertThat(result.getLicenseKey()).startsWith("AURA-");
        assertThat(result.getLicenseKey()).hasSizeGreaterThan("AURA-".length());
    }

    @Test
    void issueLicense_deactivatesAnyExistingActiveLicenseFirst() {
        User target = user(USER_ID, "alice");
        License existing = new License();
        existing.setActive(true);
        existing.setTier(LicenseTier.BRONZE);
        existing.setUser(target);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(target));
        when(licenseRepository.findByUser(target)).thenReturn(List.of(existing));
        when(licenseRepository.save(any(License.class))).thenAnswer(inv -> inv.getArgument(0));

        service.issueLicense(USER_ID, LicenseTier.DIAMOND, null);

        // The previously active license is retired so the user has a single active license.
        assertThat(existing.isActive()).isFalse();
    }

    @Test
    void issueLicense_unknownUser_throwsNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueLicense(USER_ID, LicenseTier.GOLD, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void resolveCurrentLicense_returnsActiveLicenseOfAuthenticatedUser() {
        User current = user(USER_ID, "alice");
        License license = new License();
        license.setTier(LicenseTier.SILVER);
        license.setUser(current);
        license.setActive(true);

        when(entityAccessService.currentUser()).thenReturn(current);
        when(licenseRepository.findByUserAndActiveTrue(current)).thenReturn(Optional.of(license));

        assertThat(service.resolveCurrentLicense()).isSameAs(license);
        assertThat(service.currentTier()).isEqualTo(LicenseTier.SILVER);
        // Limits are read straight from the tier (the single source of truth).
        assertThat(service.currentMaxKeywords()).isEqualTo(LicenseTier.SILVER.getMaxKeywords());
        assertThat(service.currentMaxEntities()).isEqualTo(LicenseTier.SILVER.getMaxEntities());
        assertThat(service.currentMaxMentionsPerMonth())
                .isEqualTo(LicenseTier.SILVER.getMaxMentionsPerMonth());
        assertThat(service.currentCollectionFrequency())
                .isEqualTo(LicenseTier.SILVER.getCollectionFrequency());
    }

    @Test
    void resolveCurrentLicense_noActiveLicense_throwsNotFound() {
        User current = user(USER_ID, "alice");
        when(entityAccessService.currentUser()).thenReturn(current);
        when(licenseRepository.findByUserAndActiveTrue(current)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveCurrentLicense())
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateLicense_appliesOnlyProvidedFields() {
        License license = new License();
        license.setTier(LicenseTier.BRONZE);
        license.setActive(true);
        when(licenseRepository.findById(1L)).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(inv -> inv.getArgument(0));

        // Only the active flag is supplied; tier must be left unchanged.
        service.updateLicense(1L, null, false);

        ArgumentCaptor<License> captor = ArgumentCaptor.forClass(License.class);
        verify(licenseRepository).save(captor.capture());
        assertThat(captor.getValue().getTier()).isEqualTo(LicenseTier.BRONZE);
        assertThat(captor.getValue().isActive()).isFalse();
    }
}
