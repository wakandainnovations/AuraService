package com.aura.service.config;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.CheckpointDefaultsService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointDefaultsBackfillTest {

    private final ManagedEntityRepository entityRepository = mock(ManagedEntityRepository.class);
    private final CheckpointDefaultsService checkpointDefaultsService = mock(CheckpointDefaultsService.class);
    private final CheckpointDefaultsBackfill backfill =
            new CheckpointDefaultsBackfill(entityRepository, checkpointDefaultsService);

    private ManagedEntity movie(Long id) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(id);
        entity.setType("MOVIE");
        return entity;
    }

    @Test
    void seedsDefaultsForEveryMovie() {
        ManagedEntity a = movie(1L);
        ManagedEntity b = movie(2L);
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of(a, b));

        backfill.run(null);

        verify(checkpointDefaultsService).seedDefaults(a);
        verify(checkpointDefaultsService).seedDefaults(b);
    }

    @Test
    void isNoOpWhenNoMovies() {
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of());

        backfill.run(null);

        verify(checkpointDefaultsService, never()).seedDefaults(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isSafeToRunTwice() {
        ManagedEntity a = movie(1L);
        when(entityRepository.findByType("MOVIE")).thenReturn(List.of(a));

        backfill.run(null);
        backfill.run(null);

        verify(checkpointDefaultsService, org.mockito.Mockito.times(2)).seedDefaults(a);
    }
}
