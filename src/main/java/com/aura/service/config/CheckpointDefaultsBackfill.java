package com.aura.service.config;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.CheckpointDefaultsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the 9 default lifecycle-stage checkpoints (see {@link com.aura.service.service.CheckpointStageCatalog})
 * onto every MOVIE entity that predates this feature. {@link CheckpointDefaultsService#seedDefaults}
 * is itself idempotent (it checks for an existing default checkpoint before creating any), so this
 * runner can call it unconditionally on every startup, mirroring {@link CheckpointTypeBackfill}.
 *
 * <p>Runs after {@link CheckpointTypeBackfill} via {@link Order}.
 */
@Slf4j
@Component
@Order(103)
@RequiredArgsConstructor
public class CheckpointDefaultsBackfill implements ApplicationRunner {

    private final ManagedEntityRepository entityRepository;
    private final CheckpointDefaultsService checkpointDefaultsService;

    @Override
    public void run(ApplicationArguments args) {
        List<ManagedEntity> movies = entityRepository.findByType("MOVIE");
        if (movies.isEmpty()) {
            return;
        }

        for (ManagedEntity movie : movies) {
            checkpointDefaultsService.seedDefaults(movie);
        }
        log.info("Checked default lifecycle checkpoints for {} movie(s)", movies.size());
    }
}
