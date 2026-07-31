package com.aura.service.dto;

import com.aura.service.enums.GraphRelationType;

import java.time.Instant;

/**
 * A {@link com.aura.service.entity.GraphEdge} shaped for a force-directed graph UI: {@code source} and
 * {@code target} match {@link GraphNodeDto#id()} values, using the naming most force-directed graph
 * libraries (e.g. d3-force) expect for a link's endpoints.
 */
public record GraphEdgeDto(
        Long id,
        Long source,
        Long target,
        GraphRelationType relationType,
        Instant timestamp,
        Double weight) {
}
