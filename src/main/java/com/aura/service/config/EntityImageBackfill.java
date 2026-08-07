package com.aura.service.config;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.EntityImageMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Matches {@code managed_entities} rows with no poster image yet to a file in the configured
 * {@code entity.images.base-path} directory, by normalized name (case/whitespace/punctuation
 * insensitive — e.g. "GD Naidu" matches {@code GDNaidu.jpeg} and "Balan: The Boy" matches
 * {@code Balan_The_Boy.jpg}).
 *
 * <p>Only the filename is stored on the entity (see {@link ManagedEntity#getImagePath()}), never the
 * absolute path, so the directory itself stays configurable without touching data. Idempotent — once
 * every row has an image (or no match exists for it), it does nothing — so it's safe on every startup
 * and naturally picks up newly-dropped image files for previously-unmatched entities.
 *
 * <p>Runs after {@link EntityOwnerBackfill} via {@link Order}.
 */
@Slf4j
@Component
@Order(101)
@RequiredArgsConstructor
public class EntityImageBackfill implements ApplicationRunner {

    private final ManagedEntityRepository entityRepository;
    private final EntityImageMatcher imageMatcher;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ManagedEntity> unmatched = entityRepository.findByImagePathIsNull();
        if (unmatched.isEmpty()) {
            return;
        }

        Map<String, String> filesByNormalizedName = imageMatcher.listImageFilesByNormalizedName();
        if (filesByNormalizedName.isEmpty()) {
            log.warn("Skipping image backfill for {} entity(ies): no readable image files", unmatched.size());
            return;
        }

        int matched = 0;
        for (ManagedEntity entity : unmatched) {
            String file = filesByNormalizedName.get(imageMatcher.normalize(entity.getName()));
            if (file != null) {
                entity.setImagePath(file);
                matched++;
            }
        }
        if (matched > 0) {
            entityRepository.saveAll(unmatched);
        }
        log.info("Backfilled poster image for {}/{} managed entity(ies)", matched, unmatched.size());
    }
}
