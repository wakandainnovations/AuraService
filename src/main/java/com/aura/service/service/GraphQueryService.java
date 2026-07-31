package com.aura.service.service;

import com.aura.service.dto.GraphSubgraphResponse;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.enums.GraphRelationType;
import org.springframework.data.domain.Pageable;

import java.time.Instant;

/**
 * Read side of the graph feature: bounded subgraph traversal for the graph UI, as opposed to
 * {@link GraphSyncService}, which writes nodes/edges from ingested data.
 */
public interface GraphQueryService {

    /**
     * The bounded subgraph reachable from the given movie's {@code GraphNode}, breadth-first out to
     * {@code depth} hops. Every filter is optional (null/{@code GraphRelationType} etc. disables it).
     * {@code pageable} windows the edges considered at each hop, so a movie with a huge fan-out doesn't
     * force loading its entire neighborhood in one call — see {@link GraphSubgraphResponse#hasMore()}.
     *
     * @return an empty subgraph (not a 404) if {@code movieId} has no {@code MOVIE} node yet — a movie
     *         can validly exist before anything about it has synced into the graph.
     */
    GraphSubgraphResponse getMovieSubgraph(
            Long movieId,
            GraphNodeType nodeTypeFilter,
            GraphRelationType relationTypeFilter,
            Instant from,
            Instant to,
            int depth,
            Pageable pageable);
}
