package com.aura.service.controller;

import com.aura.service.dto.CheckpointResponse;
import com.aura.service.dto.CreateCheckpointRequest;
import com.aura.service.service.CheckpointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/checkpoints")
@RequiredArgsConstructor
public class CheckpointController {

    private final CheckpointService checkpointService;

    @PostMapping
    public ResponseEntity<CheckpointResponse> create(@Valid @RequestBody CreateCheckpointRequest request) {
        CheckpointResponse response = checkpointService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/entity/{entityId}")
    public ResponseEntity<List<CheckpointResponse>> listByEntity(@PathVariable("entityId") Long entityId) {
        return ResponseEntity.ok(checkpointService.listByEntity(entityId));
    }

    @DeleteMapping("/{checkpointId}")
    public ResponseEntity<Void> delete(@PathVariable("checkpointId") Long checkpointId) {
        checkpointService.delete(checkpointId);
        return ResponseEntity.noContent().build();
    }
}
