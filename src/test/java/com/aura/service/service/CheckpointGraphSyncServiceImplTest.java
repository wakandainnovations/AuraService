package com.aura.service.service;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.enums.GraphRelationType;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.GraphEdgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link CheckpointGraphSyncServiceImpl}'s single derivation rule: an OCCURRED_ON edge from the
 * checkpoint's CHECKPOINT node to its {@code managedEntity}'s MOVIE or ACTOR node, timestamped at
 * midnight UTC on the checkpoint's date. There is no action/type field on {@link Checkpoint} to branch
 * on, so unlike {@link GraphSyncServiceImplTest} there's only one relation to verify. Node
 * materialization is delegated to {@link GraphNodeFactory} (already covered by
 * {@link CheckpointGraphNodeAdapterTest}/{@link MovieGraphNodeAdapterTest}/{@link ActorGraphNodeAdapterTest})
 * — here it's mocked as a collaborator. Collaborators are mocked as interfaces
 * ({@link GraphEdgeRepository}, {@link CheckpointRepository}, {@link GraphNodeFactory}) — never concrete
 * classes.
 */
class CheckpointGraphSyncServiceImplTest {

    private static final Long CHECKPOINT_NODE_ID = 300L;
    private static final Long MOVIE_NODE_ID = 200L;
    private static final Long ACTOR_NODE_ID = 400L;
    private static final Long ENTITY_ID = 5L;

    private GraphEdgeRepository graphEdgeRepository;
    private CheckpointRepository checkpointRepository;
    private GraphNodeFactory graphNodeFactory;
    private CheckpointGraphSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        graphEdgeRepository = mock(GraphEdgeRepository.class);
        checkpointRepository = mock(CheckpointRepository.class);
        graphNodeFactory = mock(GraphNodeFactory.class);
        service = new CheckpointGraphSyncServiceImpl(graphEdgeRepository, checkpointRepository, graphNodeFactory);

        when(graphNodeFactory.materializeCheckpoint(any())).thenReturn(nodeWithId(CHECKPOINT_NODE_ID));
    }

    @Test
    void writesOccurredOnEdgeForMovieCheckpoint() {
        Checkpoint checkpoint = checkpointOf(movieEntity());
        when(graphNodeFactory.materializeMovie(any())).thenReturn(nodeWithId(MOVIE_NODE_ID));

        service.syncCheckpoint(checkpoint);

        verify(graphEdgeRepository).save(org.mockito.ArgumentMatchers.argThat(edge ->
                edge.getRelationType() == GraphRelationType.OCCURRED_ON
                        && edge.getFromNodeId().equals(CHECKPOINT_NODE_ID)
                        && edge.getToNodeId().equals(MOVIE_NODE_ID)
                        && edge.getTimestamp().equals(
                                LocalDate.of(2026, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant())));
    }

    @Test
    void writesOccurredOnEdgeForCelebrityCheckpointAgainstActorNode() {
        Checkpoint checkpoint = checkpointOf(celebrityEntity());
        when(graphNodeFactory.materializeActor(any())).thenReturn(nodeWithId(ACTOR_NODE_ID));

        service.syncCheckpoint(checkpoint);

        verify(graphEdgeRepository).save(org.mockito.ArgumentMatchers.argThat(edge ->
                edge.getRelationType() == GraphRelationType.OCCURRED_ON
                        && edge.getFromNodeId().equals(CHECKPOINT_NODE_ID)
                        && edge.getToNodeId().equals(ACTOR_NODE_ID)));
        verify(graphNodeFactory, never()).materializeMovie(any());
    }

    @Test
    void checkpointWithUnrecognizedManagedEntityTypeIsSkipped() {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        entity.setType("SOMETHING_ELSE");
        Checkpoint checkpoint = checkpointOf(entity);

        service.syncCheckpoint(checkpoint);

        verify(graphNodeFactory, never()).materializeCheckpoint(any());
        verify(graphEdgeRepository, never()).save(any());
    }

    @Test
    void existingOccurredOnEdgeIsNotDuplicated() {
        Checkpoint checkpoint = checkpointOf(movieEntity());
        when(graphNodeFactory.materializeMovie(any())).thenReturn(nodeWithId(MOVIE_NODE_ID));
        when(graphEdgeRepository.existsByFromNodeIdAndToNodeIdAndRelationType(
                CHECKPOINT_NODE_ID, MOVIE_NODE_ID, GraphRelationType.OCCURRED_ON)).thenReturn(true);

        service.syncCheckpoint(checkpoint);

        verify(graphEdgeRepository, never()).save(any());
    }

    @Test
    void syncAllCheckpointsSyncsEveryStoredCheckpoint() {
        Checkpoint first = checkpointOf(movieEntity());
        Checkpoint second = checkpointOf(movieEntity());
        when(checkpointRepository.findAll()).thenReturn(List.of(first, second));
        when(graphNodeFactory.materializeMovie(any())).thenReturn(nodeWithId(MOVIE_NODE_ID));

        service.syncAllCheckpoints();

        verify(graphEdgeRepository, times(2)).save(any());
    }

    private static GraphNode nodeWithId(Long id) {
        GraphNode node = new GraphNode();
        node.setId(id);
        node.setType(GraphNodeType.CHECKPOINT);
        return node;
    }

    private static Checkpoint checkpointOf(ManagedEntity entity) {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.setId(1L);
        checkpoint.setManagedEntity(entity);
        checkpoint.setCheckpointDate(LocalDate.of(2026, 1, 15));
        checkpoint.setDescription("positive");
        return checkpoint;
    }

    private static ManagedEntity movieEntity() {
        ManagedEntity movie = new ManagedEntity();
        movie.setId(ENTITY_ID);
        movie.setType("MOVIE");
        movie.setName("Test Movie");
        return movie;
    }

    private static ManagedEntity celebrityEntity() {
        ManagedEntity celebrity = new ManagedEntity();
        celebrity.setId(ENTITY_ID);
        celebrity.setType("CELEBRITY");
        celebrity.setName("Test Actor");
        return celebrity;
    }
}
