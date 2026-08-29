package com.aura.service.service;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.service.CheckpointStageCatalog.StageDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Seeds the 9 default {@link CheckpointStageCatalog} lifecycle stages onto a movie, and keeps the
 * release-date-derived stages (6-9) current when the movie's releaseDate changes. Called from
 * {@link EntityService} right after a MOVIE entity is created/updated, and from
 * {@link com.aura.service.config.CheckpointDefaultsBackfill} for movies that predate this feature.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckpointDefaultsService {

    private final CheckpointRepository checkpointRepository;

    @Transactional
    public void seedDefaults(ManagedEntity entity) {
        if (checkpointRepository.existsByManagedEntityIdAndIsDefaultTrue(entity.getId())) {
            return;
        }

        List<Checkpoint> defaults = new ArrayList<>();
        for (StageDefinition def : CheckpointStageCatalog.all().values()) {
            Checkpoint checkpoint = Checkpoint.builder()
                    .managedEntity(entity)
                    .description(def.displayName())
                    .checkpointType(def.defaultCheckpointType())
                    .stage(def.stage())
                    .isDefault(true)
                    .build();

            if (def.windowComputedFromRelease()) {
                applyComputedWindow(entity, def, checkpoint, null);
            }

            defaults.add(checkpoint);
        }

        checkpointRepository.saveAll(defaults);
    }

    @Transactional
    public void recomputeReleaseDerivedStages(ManagedEntity entity) {
        List<Checkpoint> defaultCheckpoints =
                checkpointRepository.findByManagedEntityIdAndIsDefaultTrue(entity.getId());
        if (defaultCheckpoints.isEmpty()) {
            return;
        }

        List<Checkpoint> updated = new ArrayList<>();
        for (Checkpoint checkpoint : defaultCheckpoints) {
            StageDefinition def = checkpoint.getStage() != null
                    ? CheckpointStageCatalog.byStage(checkpoint.getStage()) : null;
            if (def == null || !def.windowComputedFromRelease()) {
                continue;
            }

            LocalDate previousDate = checkpoint.getCheckpointDate();
            checkpoint.setCheckpointDate(null);
            checkpoint.setWindowEndDate(null);
            applyComputedWindow(entity, def, checkpoint, checkpoint.getId());
            if (!Objects.equals(previousDate, checkpoint.getCheckpointDate())) {
                updated.add(checkpoint);
            }
        }

        if (!updated.isEmpty()) {
            checkpointRepository.saveAll(updated);
        }
    }

    /** Computes a stage-6..9 checkpoint's date/windowEndDate from releaseDate, deferring to any ad hoc
     *  checkpoint that already occupies the exact computed start date rather than violating the
     *  (managedEntity, checkpointDate) unique constraint. {@code excludeCheckpointId} is the id of the
     *  checkpoint being recomputed (so it never collides with its own prior state); null when seeding a
     *  brand-new, not-yet-saved checkpoint. */
    private void applyComputedWindow(
            ManagedEntity entity, StageDefinition def, Checkpoint target, Long excludeCheckpointId) {
        LocalDate releaseDate = entity.getReleaseDate();
        if (releaseDate == null) {
            return;
        }

        LocalDate start = releaseDate.plusDays(def.releaseOffsetStartDays());
        LocalDate end = releaseDate.plusDays(def.releaseOffsetEndDays());

        Optional<Checkpoint> collision =
                checkpointRepository.findByManagedEntityIdAndCheckpointDate(entity.getId(), start);
        if (collision.isPresent() && !Objects.equals(collision.get().getId(), excludeCheckpointId)) {
            log.warn("Skipping computed date {} for stage {} on entity {} - an existing checkpoint "
                    + "already occupies that date", start, def.stage(), entity.getId());
            return;
        }

        target.setCheckpointDate(start);
        target.setWindowEndDate(end);
    }
}
