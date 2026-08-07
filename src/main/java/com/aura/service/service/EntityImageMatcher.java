package com.aura.service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Matches an entity name to a poster file in the configured {@code entity.images.base-path}
 * directory, by normalized name (case/whitespace/punctuation insensitive — e.g. "GD Naidu"
 * matches {@code GDNaidu.jpeg}). Shared by {@link com.aura.service.config.EntityImageBackfill}
 * (bulk match on startup) and {@link EntityService} (re-match when a name changes), so the two
 * never disagree on what counts as a match.
 */
@Slf4j
@Component
public class EntityImageMatcher {

    @Value("${entity.images.base-path}")
    private String imagesBasePath;

    /** The matching filename for {@code entityName}, or null if no image file matches it. */
    public String matchFile(String entityName) {
        return listImageFilesByNormalizedName().get(normalize(entityName));
    }

    public Map<String, String> listImageFilesByNormalizedName() {
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
    public String normalize(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
