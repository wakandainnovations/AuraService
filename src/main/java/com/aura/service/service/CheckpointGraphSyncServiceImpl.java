package com.aura.service.service;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.GraphEdge;
import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.GraphRelationType;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.GraphEdgeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Derivation rules (per product decision, not inferred):
 * <ul>
 *   <li>OCCURRED_ON — always, from the checkpoint's CHECKPOINT node to its {@code managedEntity}'s
 *       MOVIE or ACTOR node, timestamped at midnight UTC on {@code checkpoint.checkpointDate}.</li>
 * </ul>
 *
 * <p><b>Known gaps</b> (not fabricated — {@link Checkpoint} genuinely has no data to support these):
 * <ul>
 *   <li>No action/type-specific relations. {@code Checkpoint} has no action/type enum in AuraService's
 *       data model — only {@code managedEntity}, {@code checkpointDate}, and a free-text
 *       {@code description} (max 20 chars, not backed by an enum). There is exactly one derivable
 *       relation, OCCURRED_ON, regardless of a checkpoint's description.</li>
 *   <li>No User edge. {@code checkpoint.getManagedEntity().getOwner()} is only the entity's owner, not
 *       necessarily who or what produced the checkpoint (checkpoints are scheduled snapshots, not user
 *       actions) — so no WATCHED/POSTED_ABOUT-style user edge is derived here.</li>
 *   <li>No Song/Trailer edges. Those entity types don't exist in AuraService's data model
 *       ({@link GraphNodeFactory} only materializes Movie/Actor/Checkpoint).</li>
 * </ul>
 */
@Slf4j
@Service
public class CheckpointGraphSyncServiceImpl implements CheckpointGraphSyncService {

    private static final String MOVIE_TYPE = "MOVIE";
    private static final String CELEBRITY_TYPE = "CELEBRITY";

    private final GraphEdgeRepository graphEdgeRepository;
    private final CheckpointRepository checkpointRepository;
    private final GraphNodeFactory graphNodeFactory;

    public CheckpointGraphSyncServiceImpl(
            GraphEdgeRepository graphEdgeRepository,
            CheckpointRepository checkpointRepository,
            GraphNodeFactory graphNodeFactory) {
        this.graphEdgeRepository = graphEdgeRepository;
        this.checkpointRepository = checkpointRepository;
        this.graphNodeFactory = graphNodeFactory;
    }

    @Override
    public void syncCheckpoint(Checkpoint checkpoint) {
        ManagedEntity entity = checkpoint.getManagedEntity();

        GraphNode entityNode;
        if (MOVIE_TYPE.equalsIgnoreCase(entity.getType())) {
            entityNode = graphNodeFactory.materializeMovie(entity);
        } else if (CELEBRITY_TYPE.equalsIgnoreCase(entity.getType())) {
            entityNode = graphNodeFactory.materializeActor(entity);
        } else {
            log.warn("Checkpoint {} references managedEntity {} with unrecognized type '{}'; skipping graph sync.",
                    checkpoint.getId(), entity.getId(), entity.getType());
            return;
        }

        GraphNode checkpointNode = graphNodeFactory.materializeCheckpoint(checkpoint);
        Instant timestamp = checkpoint.getCheckpointDate().atStartOfDay(ZoneOffset.UTC).toInstant();

        createEdgeIfAbsent(checkpointNode.getId(), entityNode.getId(), GraphRelationType.OCCURRED_ON, timestamp);
    }

    @Override
    public void syncAllCheckpoints() {
        checkpointRepository.findAll().forEach(this::syncCheckpoint);
    }

    private void createEdgeIfAbsent(
            Long fromNodeId, Long toNodeId, GraphRelationType relationType, Instant timestamp) {
        if (graphEdgeRepository.existsByFromNodeIdAndToNodeIdAndRelationType(fromNodeId, toNodeId, relationType)) {
            return;
        }
        GraphEdge edge = new GraphEdge();
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setRelationType(relationType);
        edge.setTimestamp(timestamp);
        graphEdgeRepository.save(edge);
    }
}
