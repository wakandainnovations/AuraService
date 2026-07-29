package com.aura.service.service;

import com.aura.service.entity.GraphNode;

/**
 * Converts one source domain type into a {@link GraphNode}. Implementations upsert by their source
 * type's natural key (see each adapter's dedup lookup on {@link com.aura.service.repository.GraphNodeRepository}),
 * so calling {@link #materialize} repeatedly with the same source is safe and only ever produces one node.
 *
 * <p>Defined as an interface (mirroring {@link GraphSyncService}/{@link LLMService}) so callers can mock
 * it with an interface rather than a concrete class in unit tests.
 */
public interface GraphNodeAdapter<T> {

    /** Creates or updates the {@link GraphNode} for {@code source}, returning the saved node. */
    GraphNode materialize(T source);
}
