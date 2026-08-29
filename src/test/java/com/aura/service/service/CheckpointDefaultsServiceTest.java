package com.aura.service.service;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.CheckpointStage;
import com.aura.service.repository.CheckpointRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointDefaultsServiceTest {

    private final CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
    private final CheckpointDefaultsService service = new CheckpointDefaultsService(checkpointRepository);

    private ManagedEntity movie(Long id, LocalDate releaseDate) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(id);
        entity.setType("MOVIE");
        entity.setReleaseDate(releaseDate);
        return entity;
    }

    @Test
    void isNoOpWhenDefaultsAlreadySeeded() {
        ManagedEntity entity = movie(1L, null);
        when(checkpointRepository.existsByManagedEntityIdAndIsDefaultTrue(1L)).thenReturn(true);

        service.seedDefaults(entity);

        verify(checkpointRepository, never()).saveAll(any());
    }

    @Test
    void seedsNineDefaultRowsWithNullDatesForStagesOneThroughFiveWhenNoReleaseDate() {
        ManagedEntity entity = movie(2L, null);
        when(checkpointRepository.existsByManagedEntityIdAndIsDefaultTrue(2L)).thenReturn(false);

        service.seedDefaults(entity);

        List<Checkpoint> saved = captureSaved();
        assertThat(saved).hasSize(9);
        assertThat(saved).allMatch(Checkpoint::isDefault);
        assertThat(saved).allMatch(c -> c.getCheckpointDate() == null);
        assertThat(saved).extracting(Checkpoint::getStage)
                .containsExactlyInAnyOrder(CheckpointStage.values());
    }

    @Test
    void computesDatesForStagesSixThroughNineWhenReleaseDateIsSet() {
        LocalDate releaseDate = LocalDate.of(2026, 1, 1);
        ManagedEntity entity = movie(3L, releaseDate);
        when(checkpointRepository.existsByManagedEntityIdAndIsDefaultTrue(3L)).thenReturn(false);
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(anyLong(), any()))
                .thenReturn(Optional.empty());

        service.seedDefaults(entity);

        List<Checkpoint> saved = captureSaved();
        List<Checkpoint> manualStages = saved.stream()
                .filter(c -> c.getStage().ordinal() < 5)
                .toList();
        List<Checkpoint> computedStages = saved.stream()
                .filter(c -> c.getStage().ordinal() >= 5)
                .toList();

        assertThat(manualStages).allMatch(c -> c.getCheckpointDate() == null);
        assertThat(computedStages).allMatch(c -> c.getCheckpointDate() != null);
        assertThat(computedStages).allMatch(c -> c.getWindowEndDate() != null);

        Checkpoint theatrical = computedStages.stream()
                .filter(c -> c.getStage() == CheckpointStage.THEATRICAL_WINDOW)
                .findFirst().orElseThrow();
        assertThat(theatrical.getCheckpointDate()).isEqualTo(releaseDate.plusDays(1));
        assertThat(theatrical.getWindowEndDate()).isEqualTo(releaseDate.plusDays(45));
    }

    @Test
    void skipsComputedDateWhenAnotherCheckpointAlreadyOccupiesIt() {
        LocalDate releaseDate = LocalDate.of(2026, 1, 1);
        ManagedEntity entity = movie(4L, releaseDate);
        when(checkpointRepository.existsByManagedEntityIdAndIsDefaultTrue(4L)).thenReturn(false);

        Checkpoint existingCollision = Checkpoint.builder().id(999L).build();
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(4L, releaseDate.plusDays(1)))
                .thenReturn(Optional.of(existingCollision));

        service.seedDefaults(entity);

        List<Checkpoint> saved = captureSaved();
        Checkpoint theatrical = saved.stream()
                .filter(c -> c.getStage() == CheckpointStage.THEATRICAL_WINDOW)
                .findFirst().orElseThrow();
        assertThat(theatrical.getCheckpointDate()).isNull();
    }

    @Test
    void recomputeIsNoOpWhenNoDefaultsExistYet() {
        ManagedEntity entity = movie(5L, LocalDate.of(2026, 1, 1));
        when(checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(5L)).thenReturn(List.of());

        service.recomputeReleaseDerivedStages(entity);

        verify(checkpointRepository, never()).saveAll(any());
    }

    @Test
    void recomputeOnlyTouchesReleaseDerivedStages() {
        LocalDate oldReleaseDate = LocalDate.of(2026, 1, 1);
        LocalDate newReleaseDate = LocalDate.of(2026, 2, 1);
        ManagedEntity entity = movie(6L, newReleaseDate);

        Checkpoint manualStage = Checkpoint.builder()
                .id(10L).stage(CheckpointStage.TENSION_CURIOSITY).isDefault(true).build();
        Checkpoint theatrical = Checkpoint.builder()
                .id(11L).stage(CheckpointStage.THEATRICAL_WINDOW).isDefault(true)
                .checkpointDate(oldReleaseDate.plusDays(1)).windowEndDate(oldReleaseDate.plusDays(45))
                .build();

        when(checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(6L))
                .thenReturn(new ArrayList<>(List.of(manualStage, theatrical)));
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(anyLong(), any()))
                .thenReturn(Optional.empty());

        service.recomputeReleaseDerivedStages(entity);

        assertThat(manualStage.getCheckpointDate()).isNull();
        assertThat(theatrical.getCheckpointDate()).isEqualTo(newReleaseDate.plusDays(1));
        assertThat(theatrical.getWindowEndDate()).isEqualTo(newReleaseDate.plusDays(45));

        List<Checkpoint> saved = captureSaved();
        assertThat(saved).containsExactly(theatrical);
    }

    @SuppressWarnings("unchecked")
    private List<Checkpoint> captureSaved() {
        org.mockito.ArgumentCaptor<List<Checkpoint>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(checkpointRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
