package com.aura.service.config;

import com.aura.service.entity.Checkpoint;
import com.aura.service.enums.CheckpointType;
import com.aura.service.repository.CheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sets {@code checkpointType = OTHER} on any legacy {@code checkpoints} row that predates the
 * column.
 *
 * <p>The schema is managed by {@code ddl-auto=update} (no Flyway), so when {@code checkpoint_type}
 * is first added to an already-populated table the existing rows have a null type. This runner
 * gives them all {@link CheckpointType#OTHER} so the column is always populated going forward. It
 * is idempotent: once every row has a type it does nothing, so it is safe to run on every startup.
 *
 * <p>Runs after {@link EntityImageBackfill} via {@link Order}.
 */
@Slf4j
@Component
@Order(102)
@RequiredArgsConstructor
public class CheckpointTypeBackfill implements ApplicationRunner {

    private final CheckpointRepository checkpointRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Checkpoint> untyped = checkpointRepository.findByCheckpointTypeIsNull();
        if (untyped.isEmpty()) {
            return;
        }

        untyped.forEach(checkpoint -> checkpoint.setCheckpointType(CheckpointType.OTHER));
        checkpointRepository.saveAll(untyped);
        log.info("Backfilled checkpointType=OTHER for {} checkpoint(s) with no type", untyped.size());
    }
}
