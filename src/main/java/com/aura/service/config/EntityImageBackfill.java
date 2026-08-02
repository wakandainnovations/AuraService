package com.aura.service.config;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.ManagedEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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

    @Value("${entity.images.base-path}")
    private String imagesBasePath;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ManagedEntity> unmatched = entityRepository.findByImagePathIsNull();
        if (unmatched.isEmpty()) {
            return;
        }

        Map<String, String> filesByNormalizedName = listImageFilesByNormalizedName();
        if (filesByNormalizedName.isEmpty()) {
            log.warn("Skipping image backfill for {} entity(ies): no readable image files under {}",
                    unmatched.size(), imagesBasePath);
            return;
        }

        int matched = 0;
        for (ManagedEntity entity : unmatched) {
            String file = filesByNormalizedName.get(normalize(entity.getName()));
            if (file != null) {
                entity.setImagePath(file);
                matched++;
            }
        }
        if (matched > 0) {
            entityRepository.saveAll(unmatched);
        }
        log.info("Backfilled poster image for {}/{} managed entity(ies) from {}",
                matched, unmatched.size(), imagesBasePath);
    }

    private Map<String, String> listImageFilesByNormalizedName() {
        Map<String, String> byName = new HashMap<>();
        Path dir = Path.of(imagesBasePath);
        if (!Files.isDirectory(dir)) {
            return byName;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                if (Files.isDirectory(file)) {
                    continue;
                }
                String filename = file.getFileName().toString();
                String baseName = filename.contains(".")
                        ? filename.substring(0, filename.lastIndexOf('.'))
                        : filename;
                byName.put(normalize(baseName), filename);
            }
        } catch (IOException e) {
            log.warn("Failed to list image files under {}: {}", imagesBasePath, e.getMessage());
        }
        return byName;
    }

    /** Lowercases and strips everything but letters/digits, so naming variants (spaces, underscores,
     *  punctuation like the colon in "Balan: The Boy") all collapse to the same key. */
    private String normalize(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
