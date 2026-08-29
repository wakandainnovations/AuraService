package com.aura.service.service;

import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@code ownerId} and {@code imagePath} on {@link EntityDetailResponse} — added so the
 * detail endpoint exposes every {@link ManagedEntity} column, not just the fields the UI already
 * rendered (owner was previously omitted entirely, and only the derived {@code imageUrl} was sent).
 */
class EntityServiceDetailFieldsTest {

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
    }

    @Test
    void getEntityByIdIncludesOwnerIdAndRawImagePath() {
        User owner = new User();
        owner.setId(42L);

        ManagedEntity entity = new ManagedEntity();
        entity.setId(9L);
        entity.setName("KGF Chapter 2");
        entity.setType("MOVIE");
        entity.setOwner(owner);
        entity.setImagePath("kgf-chapter-2.jpg");
        when(entityAccess.assertOwnedByCurrentUser(9L)).thenReturn(entity);

        EntityDetailResponse response = service.getEntityById("MOVIE", 9L);

        assertThat(response.getOwnerId()).isEqualTo(42L);
        assertThat(response.getImagePath()).isEqualTo("kgf-chapter-2.jpg");
        assertThat(response.getImageUrl()).isEqualTo("/entities/movie/9/image");
    }

    @Test
    void getEntityByIdLeavesOwnerIdAndImagePathNullWhenUnset() {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(11L);
        entity.setName("Untitled Project");
        entity.setType("MOVIE");
        when(entityAccess.assertOwnedByCurrentUser(11L)).thenReturn(entity);

        EntityDetailResponse response = service.getEntityById("MOVIE", 11L);

        assertThat(response.getOwnerId()).isNull();
        assertThat(response.getImagePath()).isNull();
        assertThat(response.getImageUrl()).isNull();
    }
}
