package com.aura.service.service;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;

/**
 * Materializes existing domain entities into {@link GraphNode} rows, one method per source type. Each
 * method delegates to a dedicated {@link GraphNodeAdapter} that upserts by the source's natural key, so
 * re-materializing the same entity is safe.
 *
 * <p>Only source types with a real backing entity are covered: MOVIE/CELEBRITY {@link ManagedEntity}
 * rows and {@link Checkpoint} rows. There is no Song or Trailer entity in the data model (those
 * {@code GraphNodeType} values have no backing table), and AuraService's own {@code User} login accounts
 * are a distinct population from the mention-author USER nodes {@link GraphSyncService} already
 * maintains — neither is materialized here.
 */
public interface GraphNodeFactory {

    /** Materializes a MOVIE-typed {@code ManagedEntity} into a MOVIE node. */
    GraphNode materializeMovie(ManagedEntity movie);

    /** Materializes a CELEBRITY-typed {@code ManagedEntity} into an ACTOR node. */
    GraphNode materializeActor(ManagedEntity celebrity);

    /** Materializes a {@code Checkpoint} into a CHECKPOINT node. */
    GraphNode materializeCheckpoint(Checkpoint checkpoint);
}
