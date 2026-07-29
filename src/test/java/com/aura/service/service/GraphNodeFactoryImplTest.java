package com.aura.service.service;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers only delegation to the injected adapters (mocked as {@link GraphNodeAdapter} interfaces, never
 * concrete classes) — each adapter's own materialization/upsert behavior is covered by its own test.
 */
class GraphNodeFactoryImplTest {

    @SuppressWarnings("unchecked")
    private final GraphNodeAdapter<ManagedEntity> movieGraphNodeAdapter = mock(GraphNodeAdapter.class);
    @SuppressWarnings("unchecked")
    private final GraphNodeAdapter<ManagedEntity> actorGraphNodeAdapter = mock(GraphNodeAdapter.class);
    @SuppressWarnings("unchecked")
    private final GraphNodeAdapter<Checkpoint> checkpointGraphNodeAdapter = mock(GraphNodeAdapter.class);

    private GraphNodeFactoryImpl factory;

    @BeforeEach
    void setUp() {
        factory = new GraphNodeFactoryImpl(movieGraphNodeAdapter, actorGraphNodeAdapter, checkpointGraphNodeAdapter);
    }

    @Test
    void materializeMovieDelegatesToMovieAdapterOnly() {
        ManagedEntity movie = new ManagedEntity();
        GraphNode expected = new GraphNode();
        when(movieGraphNodeAdapter.materialize(movie)).thenReturn(expected);

        GraphNode actual = factory.materializeMovie(movie);

        assertThat(actual).isSameAs(expected);
        verifyNoInteractions(actorGraphNodeAdapter, checkpointGraphNodeAdapter);
    }

    @Test
    void materializeActorDelegatesToActorAdapterOnly() {
        ManagedEntity celebrity = new ManagedEntity();
        GraphNode expected = new GraphNode();
        when(actorGraphNodeAdapter.materialize(celebrity)).thenReturn(expected);

        GraphNode actual = factory.materializeActor(celebrity);

        assertThat(actual).isSameAs(expected);
        verifyNoInteractions(movieGraphNodeAdapter, checkpointGraphNodeAdapter);
    }

    @Test
    void materializeCheckpointDelegatesToCheckpointAdapterOnly() {
        Checkpoint checkpoint = new Checkpoint();
        GraphNode expected = new GraphNode();
        when(checkpointGraphNodeAdapter.materialize(checkpoint)).thenReturn(expected);

        GraphNode actual = factory.materializeCheckpoint(checkpoint);

        assertThat(actual).isSameAs(expected);
        verifyNoInteractions(movieGraphNodeAdapter, actorGraphNodeAdapter);
    }
}
