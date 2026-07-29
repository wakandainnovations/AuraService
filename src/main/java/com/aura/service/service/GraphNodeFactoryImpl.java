package com.aura.service.service;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class GraphNodeFactoryImpl implements GraphNodeFactory {

    private final GraphNodeAdapter<ManagedEntity> movieGraphNodeAdapter;
    private final GraphNodeAdapter<ManagedEntity> actorGraphNodeAdapter;
    private final GraphNodeAdapter<Checkpoint> checkpointGraphNodeAdapter;

    public GraphNodeFactoryImpl(
            @Qualifier("movieGraphNodeAdapter") GraphNodeAdapter<ManagedEntity> movieGraphNodeAdapter,
            @Qualifier("actorGraphNodeAdapter") GraphNodeAdapter<ManagedEntity> actorGraphNodeAdapter,
            @Qualifier("checkpointGraphNodeAdapter") GraphNodeAdapter<Checkpoint> checkpointGraphNodeAdapter) {
        this.movieGraphNodeAdapter = movieGraphNodeAdapter;
        this.actorGraphNodeAdapter = actorGraphNodeAdapter;
        this.checkpointGraphNodeAdapter = checkpointGraphNodeAdapter;
    }

    @Override
    public GraphNode materializeMovie(ManagedEntity movie) {
        return movieGraphNodeAdapter.materialize(movie);
    }

    @Override
    public GraphNode materializeActor(ManagedEntity celebrity) {
        return actorGraphNodeAdapter.materialize(celebrity);
    }

    @Override
    public GraphNode materializeCheckpoint(Checkpoint checkpoint) {
        return checkpointGraphNodeAdapter.materialize(checkpoint);
    }
}
