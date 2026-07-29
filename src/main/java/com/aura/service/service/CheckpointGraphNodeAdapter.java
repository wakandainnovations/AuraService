package com.aura.service.service;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.GraphNode;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.repository.GraphNodeRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Materializes a {@link Checkpoint} into a CHECKPOINT {@link GraphNode}, keyed by the checkpoint's id. */
@Component("checkpointGraphNodeAdapter")
public class CheckpointGraphNodeAdapter implements GraphNodeAdapter<Checkpoint> {

    private static final String ATTR_CHECKPOINT_ID = "checkpointId";
    private static final String ATTR_MANAGED_ENTITY_ID = "managedEntityId";
    private static final String ATTR_CHECKPOINT_DATE = "checkpointDate";
    private static final String ATTR_DESCRIPTION = "description";

    private final GraphNodeRepository graphNodeRepository;

    public CheckpointGraphNodeAdapter(GraphNodeRepository graphNodeRepository) {
        this.graphNodeRepository = graphNodeRepository;
    }

    @Override
    public GraphNode materialize(Checkpoint checkpoint) {
        GraphNode node = graphNodeRepository.findCheckpointNodeByCheckpointId(checkpoint.getId())
                .orElseGet(GraphNode::new);
        node.setType(GraphNodeType.CHECKPOINT);
        node.setOwner(checkpoint.getManagedEntity().getOwner());

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_CHECKPOINT_ID, checkpoint.getId());
        attributes.put(ATTR_MANAGED_ENTITY_ID, checkpoint.getManagedEntity().getId());
        attributes.put(ATTR_CHECKPOINT_DATE, checkpoint.getCheckpointDate().toString());
        attributes.put(ATTR_DESCRIPTION, checkpoint.getDescription());
        node.setAttributes(attributes);

        return graphNodeRepository.save(node);
    }
}
