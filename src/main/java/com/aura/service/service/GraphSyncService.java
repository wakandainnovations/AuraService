package com.aura.service.service;

import com.aura.service.entity.Mention;

/**
 * Derives {@code GraphNode}/{@code GraphEdge} rows from {@link Mention} data. A mention's author becomes
 * (or reuses) a USER node, each MOVIE {@code ManagedEntity} it's linked to becomes (or reuses) a MOVIE
 * node, and a POSTED_ABOUT edge is always written between them. RETWEETED and WATCHED edges are added
 * when the post's content indicates them — see {@link GraphSyncServiceImpl} for the detection rules.
 *
 * <p>Defined as an interface (mirroring {@link LLMService}/{@link EntityAccessService}) so callers can
 * mock it with an interface rather than a concrete class in unit tests.
 */
public interface GraphSyncService {

    /** Syncs a single mention's graph nodes/edges. Idempotent: re-syncing the same mention is a no-op. */
    void syncMention(Mention mention);

    /** Syncs every mention currently stored. Intended for backfilling the graph from existing data. */
    void syncAllMentions();
}
