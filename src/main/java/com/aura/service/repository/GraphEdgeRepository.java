package com.aura.service.repository;

import com.aura.service.entity.GraphEdge;
import com.aura.service.enums.GraphRelationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface GraphEdgeRepository extends JpaRepository<GraphEdge, Long> {

    List<GraphEdge> findByFromNodeId(Long fromNodeId);

    List<GraphEdge> findByToNodeId(Long toNodeId);

    List<GraphEdge> findByFromNodeIdAndRelationType(Long fromNodeId, GraphRelationType relationType);

    List<GraphEdge> findByToNodeIdAndRelationType(Long toNodeId, GraphRelationType relationType);

    void deleteByFromNodeIdOrToNodeId(Long fromNodeId, Long toNodeId);

    // Idempotency guard for graph-sync (see GraphSyncServiceImpl): re-syncing the same mention (or a
    // mention whose author/movie pair was already synced from a different post) must not duplicate
    // the user-movie edge for a given relation.
    boolean existsByFromNodeIdAndToNodeIdAndRelationType(
            Long fromNodeId, Long toNodeId, GraphRelationType relationType);

    /**
     * One hop of a subgraph traversal (see {@code GraphQueryServiceImpl}): every edge touching any of
     * {@code nodeIds}, in either direction, newest first, with each filter optional. Follows
     * {@code AuditLogRepository.search}'s boolean-flag convention rather than {@code :param IS NULL} —
     * Postgres can't infer the type of a bare {@code NULL} bind.
     */
    @Query("SELECT e FROM GraphEdge e WHERE " +
            "(e.fromNodeId IN :nodeIds OR e.toNodeId IN :nodeIds) AND " +
            "(:filterRelationType = false OR e.relationType = :relationType) AND " +
            "(:filterFrom = false OR e.timestamp >= :from) AND " +
            "(:filterTo = false OR e.timestamp <= :to) " +
            "ORDER BY e.timestamp DESC")
    Page<GraphEdge> findByNodeIdsWithFilters(
            @Param("nodeIds") List<Long> nodeIds,
            @Param("filterRelationType") boolean filterRelationType,
            @Param("relationType") GraphRelationType relationType,
            @Param("filterFrom") boolean filterFrom,
            @Param("from") Instant from,
            @Param("filterTo") boolean filterTo,
            @Param("to") Instant to,
            Pageable pageable);
}
