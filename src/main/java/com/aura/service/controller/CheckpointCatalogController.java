package com.aura.service.controller;

import com.aura.service.dto.AnchorTypeDefinitionResponse;
import com.aura.service.dto.CheckpointStageDefinitionResponse;
import com.aura.service.service.AnchorTypeCatalog;
import com.aura.service.service.CheckpointStageCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Static, ungated reference data describing the 9 default lifecycle stages and the 4 anchor-typology
 * options — plain educational/marketing copy, not user data, so (like {@code GET /api/license/features})
 * it's returned directly rather than wrapped in an {@link com.aura.service.dto.EntitledResponse}.
 */
@RestController
@RequestMapping("/api/checkpoints/catalog")
public class CheckpointCatalogController {

    @GetMapping("/stages")
    public List<CheckpointStageDefinitionResponse> stages() {
        return CheckpointStageCatalog.all().values().stream()
                .map(def -> new CheckpointStageDefinitionResponse(
                        def.stage(),
                        def.stageNumber(),
                        def.displayName(),
                        def.objective(),
                        def.checkpointQuestion(),
                        def.windowDescription(),
                        def.windowComputedFromRelease()))
                .toList();
    }

    @GetMapping("/anchor-types")
    public List<AnchorTypeDefinitionResponse> anchorTypes() {
        return AnchorTypeCatalog.all().values().stream()
                .map(def -> new AnchorTypeDefinitionResponse(
                        def.type(),
                        def.name(),
                        def.function(),
                        def.barrierAddressed(),
                        def.example()))
                .toList();
    }
}
