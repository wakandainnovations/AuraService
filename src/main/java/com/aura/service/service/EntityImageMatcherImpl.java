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

@Slf4j
@Component
public class EntityImageMatcherImpl implements EntityImageMatcher {

    @Value("${entity.images.base-path}")
    private String imagesBasePath;

    @Override
    public String matchFile(String entityName) {
        return listImageFilesByNormalizedName().get(normalize(entityName));
    }

    @Override
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

    @Override
    public String normalize(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
