package com.aura.service.service;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointGraphNodeAdapterTest {

    private GraphNodeRepository graphNodeRepository;
    private CheckpointGraphNodeAdapter adapter;

    @BeforeEach
    void setUp() {
        graphNodeRepository = mock(GraphNodeRepository.class);
        adapter = new CheckpointGraphNodeAdapter(graphNodeRepository);
        when(graphNodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void materializesNewCheckpointIntoCorrectlyTypedNode() {
        User owner = new User();
        owner.setId(1L);
        ManagedEntity movie = new ManagedEntity();
        movie.setId(5L);
        movie.setOwner(owner);
        Checkpoint checkpoint = Checkpoint.builder()
                .id(42L)
                .managedEntity(movie)
                .checkpointDate(LocalDate.of(2026, 1, 15))
                .description("Trailer drop")
                .build();
        when(graphNodeRepository.findCheckpointNodeByCheckpointId(42L)).thenReturn(Optional.empty());

        GraphNode node = adapter.materialize(checkpoint);

        assertThat(node.getType()).isEqualTo(GraphNodeType.CHECKPOINT);
        assertThat(node.getOwner()).isEqualTo(owner);
        assertThat(node.getAttributes())
                .containsEntry("checkpointId", 42L)
                .containsEntry("managedEntityId", 5L)
                .containsEntry("checkpointDate", "2026-01-15")
                .containsEntry("description", "Trailer drop");
    }

    @Test
    void reMaterializingReusesExistingNodeAndRefreshesAttributes() {
        GraphNode existing = new GraphNode();
        existing.setId(400L);
        existing.setType(GraphNodeType.CHECKPOINT);
        ManagedEntity movie = new ManagedEntity();
        movie.setId(5L);
        Checkpoint checkpoint = Checkpoint.builder()
                .id(42L)
                .managedEntity(movie)
                .checkpointDate(LocalDate.of(2026, 1, 15))
                .description("Updated description")
                .build();
        when(graphNodeRepository.findCheckpointNodeByCheckpointId(42L)).thenReturn(Optional.of(existing));

        GraphNode node = adapter.materialize(checkpoint);

        assertThat(node.getId()).isEqualTo(400L);
        assertThat(node.getAttributes()).containsEntry("description", "Updated description");
        verify(graphNodeRepository).save(existing);
    }
}
