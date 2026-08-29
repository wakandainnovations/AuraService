package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.UpdateEntityRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the rule that a movie's {@code language} is derived from its {@code industry}
 * on create and update: a recognized regional industry dictates the language (overriding
 * any client-supplied value), while an unrecognized industry falls back to the supplied
 * language.
 */
class EntityServiceLanguageMappingTest {

    private ManagedEntityRepository entityRepository;
    private EntityAccessService entityAccess;
    private EntityService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
        MentionRepository mentionRepository = mock(MentionRepository.class);
        entityAccess = mock(EntityAccessService.class);
        LicenseService licenseService = mock(LicenseService.class);
        IndianMacroEconomicDataService macroEconomicDataService = mock(IndianMacroEconomicDataService.class);
        service = new EntityService(entityRepository, checkpointRepository, mentionRepository,
                entityAccess, licenseService, macroEconomicDataService, mock(EntityImageMatcher.class), mock(CheckpointDefaultsService.class));
        // save() returns the entity it was given so the response reflects the resolved language.
        when(entityRepository.save(any(ManagedEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // Every create stamps the current user as owner.
        when(entityAccess.currentUser()).thenReturn(new User());
        // Caps are not under test here — keep them comfortably high so create/update never trips them.
        when(licenseService.currentMaxEntities()).thenReturn(1000);
        when(licenseService.currentMaxKeywords()).thenReturn(1000);
    }

    @ParameterizedTest
    @CsvSource({
            "Sandalwood, Kannada",
            "Bollywood, Hindi",
            "Tollywood, Telugu",
            "Kollywood, Tamil",
            "Mollywood, Malayalam",
    })
    void createDerivesLanguageFromRecognizedIndustry(String industry, String expectedLanguage) {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("Some Movie");
        request.setIndustry(industry);
        // A wrong client-supplied language must be overridden by the industry.
        request.setLanguage("English");

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getIndustry()).isEqualTo(industry);
        assertThat(response.getLanguage()).isEqualTo(expectedLanguage);
    }

    @Test
    void createMatchesIndustryCaseInsensitively() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("Kantara");
        request.setIndustry("sandalwood");
        request.setLanguage("English");

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getLanguage()).isEqualTo("Kannada");
    }

    @Test
    void createKeepsSuppliedLanguageForUnrecognizedIndustry() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("Dune");
        request.setIndustry("Hollywood");
        request.setLanguage("English");

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getIndustry()).isEqualTo("Hollywood");
        assertThat(response.getLanguage()).isEqualTo("English");
    }

    @Test
    void updateDerivesLanguageFromRecognizedIndustryOverridingSuppliedValue() {
        ManagedEntity existing = new ManagedEntity();
        existing.setId(7L);
        existing.setName("KGF");
        existing.setType("MOVIE");
        existing.setLanguage("Hindi");
        existing.setIndustry("Bollywood");
        when(entityAccess.assertOwnedByCurrentUser(7L)).thenReturn(existing);

        UpdateEntityRequest request = new UpdateEntityRequest();
        request.setName("KGF");
        request.setIndustry("Sandalwood");
        // Stale/wrong language that must be corrected to match the new industry.
        request.setLanguage("Hindi");

        EntityDetailResponse response = service.updateEntity("MOVIE", 7L, request);

        assertThat(response.getIndustry()).isEqualTo("Sandalwood");
        assertThat(response.getLanguage()).isEqualTo("Kannada");
    }

    @Test
    void updateKeepsSuppliedLanguageForUnrecognizedIndustry() {
        ManagedEntity existing = new ManagedEntity();
        existing.setId(8L);
        existing.setName("Oppenheimer");
        existing.setType("MOVIE");
        when(entityAccess.assertOwnedByCurrentUser(8L)).thenReturn(existing);

        UpdateEntityRequest request = new UpdateEntityRequest();
        request.setName("Oppenheimer");
        request.setIndustry("Hollywood");
        request.setLanguage("English");

        EntityDetailResponse response = service.updateEntity("MOVIE", 8L, request);

        assertThat(response.getIndustry()).isEqualTo("Hollywood");
        assertThat(response.getLanguage()).isEqualTo("English");
    }
}
