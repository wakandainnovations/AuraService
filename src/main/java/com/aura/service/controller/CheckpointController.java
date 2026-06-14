package com.aura.service.controller;

import com.aura.service.dto.CheckpointResponse;
import com.aura.service.dto.CreateCheckpointRequest;
import com.aura.service.dto.EntitledResponse;
import com.aura.service.dto.UpdateCheckpointRequest;
import com.aura.service.licensing.Feature;
import com.aura.service.service.CheckpointService;
import com.aura.service.service.EntitlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Checkpoints — a {@link Feature#CHECKPOINTS SILVER}-tier feature. Rather than rejecting under-tier
 * users with a {@code 403}, every endpoint answers {@code 200} with an {@link EntitledResponse}: an
 * entitled user gets the real data, an unentitled one gets a masked, blurred teaser (reads) or a plain
 * locked envelope (mutations, which never run for them).
 */
@RestController
@RequestMapping("/api/checkpoints")
@RequiredArgsConstructor
public class CheckpointController {

    private final CheckpointService checkpointService;
    private final EntitlementService entitlementService;

    @PostMapping
    public EntitledResponse<CheckpointResponse> create(@Valid @RequestBody CreateCheckpointRequest request) {
        return entitlementService.gate(Feature.CHECKPOINTS, () -> checkpointService.create(request));
    }

    @GetMapping("/entity/{entityId}")
    public EntitledResponse<List<CheckpointResponse>> listByEntity(@PathVariable("entityId") Long entityId) {
        return entitlementService.evaluate(Feature.CHECKPOINTS, () -> checkpointService.listByEntity(entityId));
    }

    @PatchMapping("/{checkpointId}")
    public EntitledResponse<CheckpointResponse> update(
            @PathVariable("checkpointId") Long checkpointId,
            @Valid @RequestBody UpdateCheckpointRequest request) {
        return entitlementService.gate(Feature.CHECKPOINTS, () -> checkpointService.update(checkpointId, request));
    }

    @DeleteMapping("/{checkpointId}")
    public EntitledResponse<Void> delete(@PathVariable("checkpointId") Long checkpointId) {
        return entitlementService.gate(Feature.CHECKPOINTS, () -> {
            checkpointService.delete(checkpointId);
            return null;
        });
    }
}
