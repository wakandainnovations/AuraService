package com.aura.service.service;

import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MovieGraphNodeAdapterTest {

    private GraphNodeRepository graphNodeRepository;
    private MovieGraphNodeAdapter adapter;

    @BeforeEach
    void setUp() {
        graphNodeRepository = mock(GraphNodeRepository.class);
        adapter = new MovieGraphNodeAdapter(graphNodeRepository);
        when(graphNodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void materializesNewMovieEntityIntoCorrectlyTypedNode() {
        User owner = new User();
        owner.setId(7L);
        ManagedEntity movie = new ManagedEntity();
        movie.setId(5L);
        movie.setType("MOVIE");
        movie.setName("Test Movie");
        movie.setOwner(owner);
        when(graphNodeRepository.findMovieNodeByManagedEntityId(5L)).thenReturn(Optional.empty());

        GraphNode node = adapter.materialize(movie);

        assertThat(node.getType()).isEqualTo(GraphNodeType.MOVIE);
        assertThat(node.getOwner()).isEqualTo(owner);
        assertThat(node.getAttributes())
                .containsEntry("managedEntityId", 5L)
                .containsEntry("name", "Test Movie");
    }

    @Test
    void reMaterializingReusesExistingNodeAndRefreshesAttributes() {
        GraphNode existing = new GraphNode();
        existing.setId(200L);
        existing.setType(GraphNodeType.MOVIE);
        ManagedEntity movie = new ManagedEntity();
        movie.setId(5L);
        movie.setType("MOVIE");
        movie.setName("Renamed Movie");
        when(graphNodeRepository.findMovieNodeByManagedEntityId(5L)).thenReturn(Optional.of(existing));

        GraphNode node = adapter.materialize(movie);

        assertThat(node.getId()).isEqualTo(200L);
        assertThat(node.getAttributes()).containsEntry("name", "Renamed Movie");
        verify(graphNodeRepository).save(existing);
    }

    @Test
    void nonMovieTypedEntityIsRejected() {
        ManagedEntity celebrity = new ManagedEntity();
        celebrity.setId(9L);
        celebrity.setType("CELEBRITY");

        assertThatThrownBy(() -> adapter.materialize(celebrity))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
