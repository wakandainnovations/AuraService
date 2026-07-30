package com.aura.service.service;

import com.aura.service.entity.Checkpoint;

/**
 * Derives {@code GraphEdge} rows from {@link Checkpoint} data. Each checkpoint is materialized into a
 * CHECKPOINT node (via {@link GraphNodeFactory}), its {@code managedEntity} is materialized into a MOVIE
 * or ACTOR node, and an OCCURRED_ON edge is written from the checkpoint to that entity, timestamped at
 * the checkpoint's date.
 *
 * <p>Checkpoint has no action/type field in AuraService's data model today — only {@code managedEntity},
 * {@code checkpointDate}, and a free-text {@code description} — so this service cannot vary relationType
 * by action, and cannot link a User (a checkpoint records a scheduled snapshot of a managed entity, not
 * a user action; {@code managedEntity.owner} is the entity's owner, not necessarily who or what produced
 * the checkpoint) or a Song/Trailer (no such entities exist in the data model). See
 * {@link CheckpointGraphSyncServiceImpl} for the full rationale.
 *
 * <p>Defined as an interface (mirroring {@link GraphSyncService}) so callers can mock it with an
 * interface rather than a concrete class in unit tests.
 */
public interface CheckpointGraphSyncService {

    /** Syncs a single checkpoint's graph node/edge. Idempotent: re-syncing the same checkpoint is a no-op. */
    void syncCheckpoint(Checkpoint checkpoint);

    /** Syncs every checkpoint currently stored. Intended for backfilling the graph from existing data. */
    void syncAllCheckpoints();
}
