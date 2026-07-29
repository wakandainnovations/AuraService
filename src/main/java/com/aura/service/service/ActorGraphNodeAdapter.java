package com.aura.service.service;

import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.repository.GraphNodeRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Materializes a CELEBRITY-typed {@link ManagedEntity} into an ACTOR {@link GraphNode}, keyed by the
 * entity's id. There is no dedicated Actor entity in the data model — CELEBRITY is the ManagedEntity
 * type used for actor/celebrity tracking (see {@link com.aura.service.enums.EntityType}), so it's the
 * adapter's source of truth rather than the free-text {@code ManagedEntity.actors} list, which has no
 * stable identity to dedup against.
 */
@Component("actorGraphNodeAdapter")
public class ActorGraphNodeAdapter implements GraphNodeAdapter<ManagedEntity> {

    private static final String CELEBRITY_TYPE = "CELEBRITY";
    private static final String ATTR_MANAGED_ENTITY_ID = "managedEntityId";
    private static final String ATTR_NAME = "name";

    private final GraphNodeRepository graphNodeRepository;

    public ActorGraphNodeAdapter(GraphNodeRepository graphNodeRepository) {
        this.graphNodeRepository = graphNodeRepository;
    }

    @Override
    public GraphNode materialize(ManagedEntity celebrity) {
        if (!CELEBRITY_TYPE.equalsIgnoreCase(celebrity.getType())) {
            throw new IllegalArgumentException(
                    "ActorGraphNodeAdapter requires a CELEBRITY-typed ManagedEntity, got: " + celebrity.getType());
        }

        GraphNode node = graphNodeRepository.findActorNodeByManagedEntityId(celebrity.getId())
                .orElseGet(GraphNode::new);
        node.setType(GraphNodeType.ACTOR);
        node.setOwner(celebrity.getOwner());

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_MANAGED_ENTITY_ID, celebrity.getId());
        attributes.put(ATTR_NAME, celebrity.getName());
        node.setAttributes(attributes);

        return graphNodeRepository.save(node);
    }
}
