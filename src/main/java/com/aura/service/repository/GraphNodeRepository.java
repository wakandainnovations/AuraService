package com.aura.service.repository;

import com.aura.service.entity.GraphNode;
import com.aura.service.enums.GraphNodeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GraphNodeRepository extends JpaRepository<GraphNode, Long> {

    List<GraphNode> findByType(GraphNodeType type);

    // Owner-scoped listing, matching ManagedEntityRepository's findByTypeAndOwnerId convention.
    List<GraphNode> findByTypeAndOwnerId(GraphNodeType type, Long ownerId);

    List<GraphNode> findByOwnerId(Long ownerId);

    // ---- Dedup lookups for graph-sync find-or-create (see GraphSyncServiceImpl) ----
    // attributes is jsonb with no dedicated identity column, so dedup goes through the JSON path
    // rather than a derived-query field.

    @Query(value = "SELECT * FROM graph_nodes WHERE type = 'USER' AND attributes ->> 'author' = :author LIMIT 1",
            nativeQuery = true)
    Optional<GraphNode> findUserNodeByAuthor(@Param("author") String author);

    @Query(value = "SELECT * FROM graph_nodes WHERE type = 'MOVIE' " +
            "AND (attributes ->> 'managedEntityId')::bigint = :entityId LIMIT 1",
            nativeQuery = true)
    Optional<GraphNode> findMovieNodeByManagedEntityId(@Param("entityId") Long entityId);

    @Query(value = "SELECT * FROM graph_nodes WHERE type = 'ACTOR' " +
            "AND (attributes ->> 'managedEntityId')::bigint = :entityId LIMIT 1",
            nativeQuery = true)
    Optional<GraphNode> findActorNodeByManagedEntityId(@Param("entityId") Long entityId);

    @Query(value = "SELECT * FROM graph_nodes WHERE type = 'CHECKPOINT' " +
            "AND (attributes ->> 'checkpointId')::bigint = :checkpointId LIMIT 1",
            nativeQuery = true)
    Optional<GraphNode> findCheckpointNodeByCheckpointId(@Param("checkpointId") Long checkpointId);
}
