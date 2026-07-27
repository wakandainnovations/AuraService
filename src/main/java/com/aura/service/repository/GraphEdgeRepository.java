package com.aura.service.repository;

import com.aura.service.entity.GraphEdge;
import com.aura.service.enums.GraphRelationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
