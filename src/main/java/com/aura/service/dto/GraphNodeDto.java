package com.aura.service.dto;

import com.aura.service.enums.GraphNodeType;

import java.util.Map;

/**
 * A {@link com.aura.service.entity.GraphNode} shaped for a force-directed graph UI: {@code id} matches
 * the {@code source}/{@code target} ids on {@link GraphEdgeDto}, and per-type display data (a MOVIE
 * node's title, a USER node's author/weight, etc.) passes through unchanged from the entity's jsonb
 * {@code attributes}.
 */
public record GraphNodeDto(
        Long id,
        GraphNodeType type,
        Map<String, Object> attributes) {
}
