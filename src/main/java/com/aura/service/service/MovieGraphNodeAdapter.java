package com.aura.service.service;

import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.repository.GraphNodeRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Materializes a MOVIE-typed {@link ManagedEntity} into a MOVIE {@link GraphNode}, keyed by the entity's id. */
@Component("movieGraphNodeAdapter")
public class MovieGraphNodeAdapter implements GraphNodeAdapter<ManagedEntity> {

    private static final String MOVIE_TYPE = "MOVIE";
    private static final String ATTR_MANAGED_ENTITY_ID = "managedEntityId";
    private static final String ATTR_NAME = "name";

    private final GraphNodeRepository graphNodeRepository;

    public MovieGraphNodeAdapter(GraphNodeRepository graphNodeRepository) {
        this.graphNodeRepository = graphNodeRepository;
    }

    @Override
    public GraphNode materialize(ManagedEntity movie) {
        if (!MOVIE_TYPE.equalsIgnoreCase(movie.getType())) {
            throw new IllegalArgumentException(
                    "MovieGraphNodeAdapter requires a MOVIE-typed ManagedEntity, got: " + movie.getType());
        }

        GraphNode node = graphNodeRepository.findMovieNodeByManagedEntityId(movie.getId())
                .orElseGet(GraphNode::new);
        node.setType(GraphNodeType.MOVIE);
        node.setOwner(movie.getOwner());

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_MANAGED_ENTITY_ID, movie.getId());
        attributes.put(ATTR_NAME, movie.getName());
        node.setAttributes(attributes);

        return graphNodeRepository.save(node);
    }
}
