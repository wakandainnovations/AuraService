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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GraphQueryServiceImpl implements GraphQueryService {

    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;

    @Override
    public GraphSubgraphResponse getMovieSubgraph(
            Long movieId,
            GraphNodeType nodeTypeFilter,
            GraphRelationType relationTypeFilter,
            Instant from,
            Instant to,
            int depth,
            Pageable pageable) {

        return graphNodeRepository.findMovieNodeByManagedEntityId(movieId)
                .map(movieNode -> traverse(
                        movieId, movieNode, nodeTypeFilter, relationTypeFilter, from, to, depth, pageable))
                .orElseGet(() -> new GraphSubgraphResponse(
                        movieId, depth, List.of(), List.of(),
                        pageable.getPageNumber(), pageable.getPageSize(), false));
    }

    /**
     * Breadth-first: each hop asks {@link GraphEdgeRepository#findByNodeIdsWithFilters} for the edges
     * touching the previous hop's newly-discovered nodes only (not the whole visited set), so a node
     * already expanded isn't re-queried on every subsequent hop.
     */
    private GraphSubgraphResponse traverse(
            Long movieId,
            GraphNode movieNode,
            GraphNodeType nodeTypeFilter,
            GraphRelationType relationTypeFilter,
            Instant from,
            Instant to,
            int depth,
            Pageable pageable) {

        Set<Long> visitedNodeIds = new LinkedHashSet<>();
        visitedNodeIds.add(movieNode.getId());
        Map<Long, GraphEdge> traversedEdges = new LinkedHashMap<>();
        List<Long> frontier = List.of(movieNode.getId());
        boolean hasMore = false;

        for (int hop = 0; hop < depth && !frontier.isEmpty(); hop++) {
            Page<GraphEdge> edgePage = graphEdgeRepository.findByNodeIdsWithFilters(
                    frontier,
                    relationTypeFilter != null, relationTypeFilter,
                    from != null, from,
                    to != null, to,
                    pageable);
            hasMore = hasMore || edgePage.hasNext();

            List<Long> nextFrontier = new ArrayList<>();
            for (GraphEdge edge : edgePage.getContent()) {
                traversedEdges.put(edge.getId(), edge);
                if (visitedNodeIds.add(edge.getFromNodeId())) {
                    nextFrontier.add(edge.getFromNodeId());
                }
                if (visitedNodeIds.add(edge.getToNodeId())) {
                    nextFrontier.add(edge.getToNodeId());
                }
            }
            frontier = nextFrontier;
        }

        Map<Long, GraphNode> nodesById = new LinkedHashMap<>();
        graphNodeRepository.findAllById(visitedNodeIds).forEach(node -> nodesById.put(node.getId(), node));

        // The root movie node always survives a nodeType filter, so the UI keeps a stable center even
        // when narrowed to e.g. only ACTOR nodes.
        Set<Long> keptNodeIds = new LinkedHashSet<>();
        keptNodeIds.add(movieNode.getId());
        for (GraphNode node : nodesById.values()) {
            if (nodeTypeFilter == null || nodeTypeFilter == node.getType()) {
                keptNodeIds.add(node.getId());
            }
        }

        List<GraphNodeDto> nodeDtos = keptNodeIds.stream()
                .map(nodesById::get)
                .filter(Objects::nonNull)
                .map(node -> new GraphNodeDto(node.getId(), node.getType(), node.getAttributes()))
                .toList();

        List<GraphEdgeDto> edgeDtos = traversedEdges.values().stream()
                .filter(edge -> keptNodeIds.contains(edge.getFromNodeId())
                        && keptNodeIds.contains(edge.getToNodeId()))
                .map(edge -> new GraphEdgeDto(
                        edge.getId(), edge.getFromNodeId(), edge.getToNodeId(),
                        edge.getRelationType(), edge.getTimestamp(), edge.getWeight()))
                .toList();

        return new GraphSubgraphResponse(
                movieId, depth, nodeDtos, edgeDtos,
                pageable.getPageNumber(), pageable.getPageSize(), hasMore);
    }
}
