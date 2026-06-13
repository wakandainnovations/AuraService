package com.aura.service.controller;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.EntityBasicInfo;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.UpdateCompetitorsRequest;
import com.aura.service.dto.UpdateEntityRequest;
import com.aura.service.dto.UpdateKeywordsRequest;
import com.aura.service.service.EntityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/entities/{entityType}")
@RequiredArgsConstructor
public class EntityController {
    
    private final EntityService entityService;
    
    @PostMapping
    public ResponseEntity<EntityDetailResponse> createEntity(@PathVariable String entityType, @Valid @RequestBody CreateEntityRequest request) {
        EntityDetailResponse response = entityService.createEntity(entityType.toUpperCase(), request);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping
    public ResponseEntity<List<EntityBasicInfo>> getAllEntities(@PathVariable String entityType) {
        List<EntityBasicInfo> entities = entityService.getAllEntities(entityType.toUpperCase());
        return ResponseEntity.ok(entities);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<EntityDetailResponse> getEntityById(@PathVariable String entityType, @PathVariable Long id) {
        EntityDetailResponse response = entityService.getEntityById(entityType.toUpperCase(), id);
        return ResponseEntity.ok(response);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<EntityDetailResponse> updateEntity(
            @PathVariable String entityType,
            @PathVariable Long id,
            @Valid @RequestBody UpdateEntityRequest request
    ) {
        EntityDetailResponse response = entityService.updateEntity(entityType.toUpperCase(), id, request);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/competitors")
    public ResponseEntity<EntityDetailResponse> updateCompetitors(
            @PathVariable String entityType,
            @PathVariable Long id,
            @Valid @RequestBody UpdateCompetitorsRequest request
    ) {
        EntityDetailResponse response = entityService.updateCompetitors(entityType.toUpperCase(), id, request);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/keywords")
    public ResponseEntity<EntityDetailResponse> updateKeywords(
            @PathVariable String entityType,
            @PathVariable Long id,
            @Valid @RequestBody UpdateKeywordsRequest request
    ) {
        EntityDetailResponse response = entityService.updateKeywords(entityType.toUpperCase(), id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntity(@PathVariable String entityType, @PathVariable Long id) {
        entityService.deleteEntity(entityType.toUpperCase(), id);
        return ResponseEntity.noContent().build();
    }
}
