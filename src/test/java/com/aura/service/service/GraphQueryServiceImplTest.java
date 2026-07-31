package com.aura.service.service;

import com.aura.service.dto.GraphEdgeDto;
import com.aura.service.dto.GraphNodeDto;
import com.aura.service.dto.GraphSubgraphResponse;
import com.aura.service.entity.GraphEdge;
import com.aura.service.entity.GraphNode;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.enums.GraphRelationType;
import com.aura.service.repository.GraphEdgeRepository;
import com.aura.service.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link GraphQueryServiceImpl}'s breadth-first subgraph traversal: hop-by-hop expansion via
 * {@link GraphEdgeRepository#findByNodeIdsWithFilters}, the "movie has no graph node yet" empty-result
 * case, the always-keep-the-root-node behavior of the {@code nodeType} filter, and {@code hasMore}
 * propagation from a hop's {@link Page#hasNext()}. Collaborators are mocked as interfaces
 * ({@link GraphNodeRepository}, {@link GraphEdgeRepository}) — never concrete classes.
 */
class GraphQueryServiceImplTest {

    private static final Long MOVIE_ENTITY_ID = 5L;
    private static final Long MOVIE_NODE_ID = 10L;
    private static final Long USER_NODE_ID = 20L;
    private static final Long CHECKPOINT_NODE_ID = 30L;
    private static final Pageable PAGEABLE = PageRequest.of(0, 100);

    private GraphNodeRepository graphNodeRepository;
    private GraphEdgeRepository graphEdgeRepository;
    private GraphQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        graphNodeRepository = mock(GraphNodeRepository.class);
        graphEdgeRepository = mock(GraphEdgeRepository.class);
        service = new GraphQueryServiceImpl(graphNodeRepository, graphEdgeRepository);
    }

    @Test
    void returnsEmptySubgraphWhenMovieHasNoGraphNodeYet() {
        when(graphNodeRepository.findMovieNodeByManagedEntityId(MOVIE_ENTITY_ID)).thenReturn(Optional.empty());

        GraphSubgraphResponse response = service.getMovieSubgraph(
                MOVIE_ENTITY_ID, null, null, null, null, 1, PAGEABLE);

        assertThat(response.nodes()).isEmpty();
        assertThat(response.edges()).isEmpty();
        assertThat(response.hasMore()).isFalse();
        assertThat(response.movieId()).isEqualTo(MOVIE_ENTITY_ID);
        verify(graphEdgeRepository, never()).findByNodeIdsWithFilters(
                anyList(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any());
    }

    @Test
    void depthOneReturnsMovieNodePlusDirectNeighbor() {
        GraphNode movieNode = nodeOf(MOVIE_NODE_ID, GraphNodeType.MOVIE);
        GraphNode userNode = nodeOf(USER_NODE_ID, GraphNodeType.USER);
        GraphEdge postedAbout = edgeOf(1L, USER_NODE_ID, MOVIE_NODE_ID, GraphRelationType.POSTED_ABOUT);

        when(graphNodeRepository.findMovieNodeByManagedEntityId(MOVIE_ENTITY_ID))
                .thenReturn(Optional.of(movieNode));
        when(graphEdgeRepository.findByNodeIdsWithFilters(
                eq(List.of(MOVIE_NODE_ID)), eq(false), any(), eq(false), any(), eq(false), any(), eq(PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(postedAbout), PAGEABLE, 1));
        when(graphNodeRepository.findAllById(any())).thenReturn(List.of(movieNode, userNode));

        GraphSubgraphResponse response = service.getMovieSubgraph(
                MOVIE_ENTITY_ID, null, null, null, null, 1, PAGEABLE);

        assertThat(response.nodes()).extracting(GraphNodeDto::id)
                .containsExactlyInAnyOrder(MOVIE_NODE_ID, USER_NODE_ID);
        assertThat(response.edges()).containsExactly(
                new GraphEdgeDto(1L, USER_NODE_ID, MOVIE_NODE_ID, GraphRelationType.POSTED_ABOUT, postedAbout.getTimestamp(), null));
        assertThat(response.hasMore()).isFalse();
    }

    @Test
    void depthTwoExpandsFromNewlyDiscoveredNodesOnly() {
        GraphNode movieNode = nodeOf(MOVIE_NODE_ID, GraphNodeType.MOVIE);
        GraphNode userNode = nodeOf(USER_NODE_ID, GraphNodeType.USER);
        GraphNode checkpointNode = nodeOf(CHECKPOINT_NODE_ID, GraphNodeType.CHECKPOINT);
        GraphEdge postedAbout = edgeOf(1L, USER_NODE_ID, MOVIE_NODE_ID, GraphRelationType.POSTED_ABOUT);
        GraphEdge occurredOn = edgeOf(2L, CHECKPOINT_NODE_ID, USER_NODE_ID, GraphRelationType.OCCURRED_ON);

        when(graphNodeRepository.findMovieNodeByManagedEntityId(MOVIE_ENTITY_ID))
                .thenReturn(Optional.of(movieNode));
        // Hop 1: expand from the movie node only.
        when(graphEdgeRepository.findByNodeIdsWithFilters(
                eq(List.of(MOVIE_NODE_ID)), eq(false), any(), eq(false), any(), eq(false), any(), eq(PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(postedAbout), PAGEABLE, 1));
        // Hop 2: expand from the newly-discovered user node only, not the movie node again.
        when(graphEdgeRepository.findByNodeIdsWithFilters(
                eq(List.of(USER_NODE_ID)), eq(false), any(), eq(false), any(), eq(false), any(), eq(PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(occurredOn), PAGEABLE, 1));
        when(graphNodeRepository.findAllById(any()))
                .thenReturn(List.of(movieNode, userNode, checkpointNode));

        GraphSubgraphResponse response = service.getMovieSubgraph(
                MOVIE_ENTITY_ID, null, null, null, null, 2, PAGEABLE);

        assertThat(response.nodes()).extracting(GraphNodeDto::id)
                .containsExactlyInAnyOrder(MOVIE_NODE_ID, USER_NODE_ID, CHECKPOINT_NODE_ID);
        assertThat(response.edges()).hasSize(2);
        verify(graphEdgeRepository, never()).findByNodeIdsWithFilters(
                eq(List.of(MOVIE_NODE_ID, USER_NODE_ID)), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any());
    }

    @Test
    void nodeTypeFilterKeepsRootMovieNodeButDropsOtherTypesAndTheirEdges() {
        GraphNode movieNode = nodeOf(MOVIE_NODE_ID, GraphNodeType.MOVIE);
        GraphNode userNode = nodeOf(USER_NODE_ID, GraphNodeType.USER);
        GraphNode checkpointNode = nodeOf(CHECKPOINT_NODE_ID, GraphNodeType.CHECKPOINT);
        GraphEdge postedAbout = edgeOf(1L, USER_NODE_ID, MOVIE_NODE_ID, GraphRelationType.POSTED_ABOUT);
        GraphEdge occurredOn = edgeOf(2L, CHECKPOINT_NODE_ID, MOVIE_NODE_ID, GraphRelationType.OCCURRED_ON);

        when(graphNodeRepository.findMovieNodeByManagedEntityId(MOVIE_ENTITY_ID))
                .thenReturn(Optional.of(movieNode));
        when(graphEdgeRepository.findByNodeIdsWithFilters(
                eq(List.of(MOVIE_NODE_ID)), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), eq(PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(postedAbout, occurredOn), PAGEABLE, 2));
        when(graphNodeRepository.findAllById(any()))
                .thenReturn(List.of(movieNode, userNode, checkpointNode));

        GraphSubgraphResponse response = service.getMovieSubgraph(
                MOVIE_ENTITY_ID, GraphNodeType.CHECKPOINT, null, null, null, 1, PAGEABLE);

        assertThat(response.nodes()).extracting(GraphNodeDto::id)
                .containsExactlyInAnyOrder(MOVIE_NODE_ID, CHECKPOINT_NODE_ID);
        assertThat(response.edges()).extracting(GraphEdgeDto::id).containsExactly(2L);
    }

    @Test
    void hasMoreReflectsAdditionalPagesAtAnyHop() {
        GraphNode movieNode = nodeOf(MOVIE_NODE_ID, GraphNodeType.MOVIE);
        GraphEdge postedAbout = edgeOf(1L, USER_NODE_ID, MOVIE_NODE_ID, GraphRelationType.POSTED_ABOUT);

        when(graphNodeRepository.findMovieNodeByManagedEntityId(MOVIE_ENTITY_ID))
                .thenReturn(Optional.of(movieNode));
        // total (101) exceeds the page size (100) -> a second page exists, so hasNext() is true.
        when(graphEdgeRepository.findByNodeIdsWithFilters(
                anyList(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), eq(PAGEABLE)))
                .thenReturn(new PageImpl<>(List.of(postedAbout), PAGEABLE, 101));
        when(graphNodeRepository.findAllById(any()))
                .thenReturn(List.of(movieNode, nodeOf(USER_NODE_ID, GraphNodeType.USER)));

        GraphSubgraphResponse response = service.getMovieSubgraph(
                MOVIE_ENTITY_ID, null, null, null, null, 1, PAGEABLE);

        assertThat(response.hasMore()).isTrue();
    }

    @Test
    void relationTypeAndDateRangeFiltersArePassedThroughToEveryHop() {
        GraphNode movieNode = nodeOf(MOVIE_NODE_ID, GraphNodeType.MOVIE);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T00:00:00Z");

        when(graphNodeRepository.findMovieNodeByManagedEntityId(MOVIE_ENTITY_ID))
                .thenReturn(Optional.of(movieNode));
        when(graphEdgeRepository.findByNodeIdsWithFilters(any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PAGEABLE, 0));
        when(graphNodeRepository.findAllById(any())).thenReturn(List.of(movieNode));

        service.getMovieSubgraph(
                MOVIE_ENTITY_ID, null, GraphRelationType.WATCHED, from, to, 1, PAGEABLE);

        verify(graphEdgeRepository).findByNodeIdsWithFilters(
                List.of(MOVIE_NODE_ID), true, GraphRelationType.WATCHED, true, from, true, to, PAGEABLE);
    }

    private static GraphNode nodeOf(Long id, GraphNodeType type) {
        GraphNode node = new GraphNode();
        node.setId(id);
        node.setType(type);
        node.setAttributes(Map.of());
        return node;
    }

    private static GraphEdge edgeOf(Long id, Long fromNodeId, Long toNodeId, GraphRelationType relationType) {
        GraphEdge edge = new GraphEdge();
        edge.setId(id);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setRelationType(relationType);
        edge.setTimestamp(Instant.parse("2026-03-01T00:00:00Z"));
        return edge;
    }
}
