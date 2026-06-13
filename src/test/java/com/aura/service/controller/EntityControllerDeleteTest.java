package com.aura.service.controller;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.service.EntityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntityControllerDeleteTest {

    private ManagedEntityRepository entityRepository;
    private CheckpointRepository checkpointRepository;
    private MentionRepository mentionRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        checkpointRepository = mock(CheckpointRepository.class);
        mentionRepository = mock(MentionRepository.class);

        EntityService service = new EntityService(entityRepository, checkpointRepository, mentionRepository);
        EntityController controller = new EntityController(service);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ManagedEntity entity(Long id, String type) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName("The Quantum Paradox");
        e.setType(type);
        return e;
    }

    @Test
    void delete_returns204AndCleansUpReferences() throws Exception {
        ManagedEntity movie = entity(1L, "MOVIE");
        when(entityRepository.findById(1L)).thenReturn(Optional.of(movie));
        when(entityRepository.findByCompetitorsId(1L)).thenReturn(List.of());

        mvc.perform(delete("/api/entities/{entityType}/{id}", "movie", 1L))
                .andExpect(status().isNoContent());

        verify(mentionRepository).deleteByManagedEntityId(1L);
        verify(checkpointRepository).deleteByManagedEntityId(1L);
        verify(entityRepository).delete(movie);
    }

    @Test
    void delete_detachesEntityFromOtherCompetitorLists() throws Exception {
        ManagedEntity target = entity(1L, "MOVIE");

        ManagedEntity referencing = entity(2L, "MOVIE");
        referencing.setCompetitors(new ArrayList<>(List.of(target)));

        when(entityRepository.findById(1L)).thenReturn(Optional.of(target));
        when(entityRepository.findByCompetitorsId(1L)).thenReturn(List.of(referencing));

        mvc.perform(delete("/api/entities/{entityType}/{id}", "movie", 1L))
                .andExpect(status().isNoContent());

        verify(entityRepository).saveAll(List.of(referencing));
        // the target must no longer appear in the other entity's competitor list
        org.junit.jupiter.api.Assertions.assertTrue(referencing.getCompetitors().isEmpty());
        verify(entityRepository).delete(target);
    }

    @Test
    void delete_returns400WhenEntityNotFound() throws Exception {
        when(entityRepository.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(delete("/api/entities/{entityType}/{id}", "movie", 999L))
                .andExpect(status().isBadRequest());

        verify(entityRepository, never()).delete(any(ManagedEntity.class));
        verify(checkpointRepository, never()).deleteByManagedEntityId(anyLong());
        verify(mentionRepository, never()).deleteByManagedEntityId(anyLong());
    }

    @Test
    void delete_returns400WhenTypeMismatch() throws Exception {
        ManagedEntity celebrity = entity(1L, "CELEBRITY");
        when(entityRepository.findById(1L)).thenReturn(Optional.of(celebrity));

        mvc.perform(delete("/api/entities/{entityType}/{id}", "movie", 1L))
                .andExpect(status().isBadRequest());

        verify(entityRepository, never()).delete(any(ManagedEntity.class));
        verify(checkpointRepository, never()).deleteByManagedEntityId(anyLong());
        verify(mentionRepository, never()).deleteByManagedEntityId(anyLong());
    }
}
