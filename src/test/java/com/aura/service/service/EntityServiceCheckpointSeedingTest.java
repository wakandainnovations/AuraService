package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.UpdateEntityRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the MOVIE-only default-checkpoint seeding/recompute hooks added to
 * {@link EntityService#createEntity} and {@link EntityService#updateEntity}.
 */
class EntityServiceCheckpointSeedingTest {

    private ManagedEntityRepository entityRepository;
    private EntityAccessService entityAccess;
    private CheckpointDefaultsService checkpointDefaultsService;
    private EntityService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
        MentionRepository mentionRepository = mock(MentionRepository.class);
        entityAccess = mock(EntityAccessService.class);
        LicenseService licenseService = mock(LicenseService.class);
        IndianMacroEconomicDataService macroEconomicDataService = mock(IndianMacroEconomicDataService.class);
        checkpointDefaultsService = mock(CheckpointDefaultsService.class);
        service = new EntityService(entityRepository, checkpointRepository, mentionRepository,
                entityAccess, licenseService, macroEconomicDataService, mock(EntityImageMatcher.class),
                checkpointDefaultsService);
        when(entityRepository.save(any(ManagedEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(entityAccess.currentUser()).thenReturn(new User());
        when(licenseService.currentMaxEntities()).thenReturn(1000);
        when(licenseService.currentMaxKeywords()).thenReturn(1000);
    }

    @Test
    void createMovieSeedsDefaultCheckpoints() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("KGF Chapter 2");

        service.createEntity("MOVIE", request);

        verify(checkpointDefaultsService).seedDefaults(any(ManagedEntity.class));
    }

    @Test
    void createCelebrityDoesNotSeedDefaultCheckpoints() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("A Star");

        service.createEntity("CELEBRITY", request);

        verify(checkpointDefaultsService, never()).seedDefaults(any());
    }

    @Test
    void updateMovieRecomputesReleaseDerivedStages() {
        ManagedEntity existing = new ManagedEntity();
        existing.setId(9L);
        existing.setName("KGF Chapter 2");
        existing.setType("MOVIE");
        when(entityAccess.assertOwnedByCurrentUser(9L)).thenReturn(existing);

        UpdateEntityRequest request = new UpdateEntityRequest();
        request.setName("KGF Chapter 2");

        service.updateEntity("MOVIE", 9L, request);

        verify(checkpointDefaultsService).recomputeReleaseDerivedStages(existing);
    }

    @Test
    void updateCelebrityDoesNotRecomputeReleaseDerivedStages() {
        ManagedEntity existing = new ManagedEntity();
        existing.setId(10L);
        existing.setName("A Star");
        existing.setType("CELEBRITY");
        when(entityAccess.assertOwnedByCurrentUser(10L)).thenReturn(existing);

        UpdateEntityRequest request = new UpdateEntityRequest();
        request.setName("A Star");

        service.updateEntity("CELEBRITY", 10L, request);

        verify(checkpointDefaultsService, never()).recomputeReleaseDerivedStages(any());
    }
}
