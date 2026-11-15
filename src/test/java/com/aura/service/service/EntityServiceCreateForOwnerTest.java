package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.enums.LicenseTier;
import com.aura.service.exception.LimitException;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Admin-on-behalf creation: owner is the target user and that user's license caps apply. */
class EntityServiceCreateForOwnerTest {

    private ManagedEntityRepository entityRepository;
    private LicenseService licenseService;
    private EntityImageMatcher imageMatcher;
    private EntityService service;
    private User owner;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        licenseService = mock(LicenseService.class);
        imageMatcher = mock(EntityImageMatcher.class);
        service = new EntityService(entityRepository, mock(CheckpointRepository.class), mock(MentionRepository.class),
                mock(EntityAccessService.class), licenseService, mock(IndianMacroEconomicDataService.class),
                imageMatcher, mock(CheckpointDefaultsService.class));
        when(entityRepository.save(any(ManagedEntity.class))).thenAnswer(inv -> {
            ManagedEntity e = inv.getArgument(0);
            e.setId(77L);
            return e;
        });
        owner = new User();
        owner.setId(42L);
        owner.setUsername("producer1");
    }

    @Test
    void ownerIsTargetUserAndPosterIsMatchedOnCreate() {
        when(licenseService.effectiveTierFor(owner)).thenReturn(LicenseTier.DIAMOND);
        when(imageMatcher.matchFile("Itllu Arjuna")).thenReturn("ItlluArjuna.jpg");
        CreateEntityRequest req = new CreateEntityRequest();
        req.setName("Itllu Arjuna");

        service.createEntityForOwner(owner, "MOVIE", req);

        ArgumentCaptor<ManagedEntity> saved = ArgumentCaptor.forClass(ManagedEntity.class);
        verify(entityRepository).save(saved.capture());
        assertThat(saved.getValue().getOwner()).isSameAs(owner);
        assertThat(saved.getValue().getImagePath()).isEqualTo("ItlluArjuna.jpg");
        // The calling admin's own limits are never consulted.
        verify(licenseService, never()).currentMaxEntities();
    }

    @Test
    void targetUsersEntityCapApplies() {
        when(licenseService.effectiveTierFor(owner)).thenReturn(LicenseTier.BRONZE); // 5 entities
        when(entityRepository.countByOwnerId(42L)).thenReturn(5L);
        CreateEntityRequest req = new CreateEntityRequest();
        req.setName("Itllu Arjuna");

        assertThatThrownBy(() -> service.createEntityForOwner(owner, "MOVIE", req)).isInstanceOf(LimitException.class);
        verify(entityRepository, never()).save(any());
    }
}
