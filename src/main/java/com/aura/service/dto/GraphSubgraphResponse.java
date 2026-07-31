package com.aura.service.dto;

import java.util.List;

/**
 * A bounded subgraph reachable from one movie's {@code GraphNode}, for direct consumption by a
 * force-directed graph UI. {@code nodes} always includes the movie's own root node (even under a
 * {@code nodeType} filter that would otherwise exclude it, so the UI keeps a stable center) plus every
 * other node reached within {@code depth} hops that survives the requested filters; {@code edges} is
 * every traversed edge whose endpoints are both present in {@code nodes}.
 *
 * <p>{@code page}/{@code size} windows the edge fan-out considered at <em>each</em> hop (not the
 * response as a whole — a popular movie's depth-1 neighborhood alone can be huge), and {@code hasMore}
 * is true if any hop had additional edges beyond that window; the caller can request the next
 * {@code page} to widen the traversal. {@code depth} echoes the (clamped) depth actually traversed.
 */
public record GraphSubgraphResponse(
        Long movieId,
        int depth,
        List<GraphNodeDto> nodes,
        List<GraphEdgeDto> edges,
        int page,
        int size,
        boolean hasMore) {
}
