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

class ActorGraphNodeAdapterTest {

    private GraphNodeRepository graphNodeRepository;
    private ActorGraphNodeAdapter adapter;

    @BeforeEach
    void setUp() {
        graphNodeRepository = mock(GraphNodeRepository.class);
        adapter = new ActorGraphNodeAdapter(graphNodeRepository);
        when(graphNodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void materializesNewCelebrityEntityIntoCorrectlyTypedActorNode() {
        User owner = new User();
        owner.setId(3L);
        ManagedEntity celebrity = new ManagedEntity();
        celebrity.setId(9L);
        celebrity.setType("CELEBRITY");
        celebrity.setName("Test Actor");
        celebrity.setOwner(owner);
        when(graphNodeRepository.findActorNodeByManagedEntityId(9L)).thenReturn(Optional.empty());

        GraphNode node = adapter.materialize(celebrity);

        assertThat(node.getType()).isEqualTo(GraphNodeType.ACTOR);
        assertThat(node.getOwner()).isEqualTo(owner);
        assertThat(node.getAttributes())
                .containsEntry("managedEntityId", 9L)
                .containsEntry("name", "Test Actor");
    }

    @Test
    void reMaterializingReusesExistingNodeAndRefreshesAttributes() {
        GraphNode existing = new GraphNode();
        existing.setId(300L);
        existing.setType(GraphNodeType.ACTOR);
        ManagedEntity celebrity = new ManagedEntity();
        celebrity.setId(9L);
        celebrity.setType("CELEBRITY");
        celebrity.setName("Renamed Actor");
        when(graphNodeRepository.findActorNodeByManagedEntityId(9L)).thenReturn(Optional.of(existing));

        GraphNode node = adapter.materialize(celebrity);

        assertThat(node.getId()).isEqualTo(300L);
        assertThat(node.getAttributes()).containsEntry("name", "Renamed Actor");
        verify(graphNodeRepository).save(existing);
    }

    @Test
    void nonCelebrityTypedEntityIsRejected() {
        ManagedEntity movie = new ManagedEntity();
        movie.setId(5L);
        movie.setType("MOVIE");

        assertThatThrownBy(() -> adapter.materialize(movie))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
