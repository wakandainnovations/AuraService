package com.aura.service.service;

import com.aura.service.dto.CheckpointResponse;
import com.aura.service.dto.CreateCheckpointRequest;
import com.aura.service.dto.UpdateCheckpointRequest;
import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.CheckpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CheckpointService {

    private final CheckpointRepository checkpointRepository;
    private final EntityAccessService entityAccessService;

    @Transactional
    public CheckpointResponse create(CreateCheckpointRequest request) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(request.getEntityId());

        Checkpoint checkpoint = Checkpoint.builder()
                .managedEntity(entity)
                .checkpointDate(request.getCheckpointDate())
                .description(request.getDescription())
                .build();

        Checkpoint saved = checkpointRepository.save(checkpoint);
        return toResponse(saved);
    }

    public List<CheckpointResponse> listByEntity(Long entityId) {
        entityAccessService.assertOwnedByCurrentUser(entityId);
        return checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(entityId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CheckpointResponse update(Long checkpointId, UpdateCheckpointRequest request) {
        Checkpoint checkpoint = checkpointRepository.findById(checkpointId)
                .orElseThrow(() -> new RuntimeException(
                        "Checkpoint not found with id " + checkpointId));
        entityAccessService.assertOwnedByCurrentUser(checkpoint.getManagedEntity().getId());

        if (request.getCheckpointDate() != null) {
            LocalDate newDate = request.getCheckpointDate();
            Long entityId = checkpoint.getManagedEntity().getId();
            checkpointRepository.findByManagedEntityIdAndCheckpointDate(entityId, newDate)
                    .filter(existing -> !existing.getId().equals(checkpointId))
                    .ifPresent(existing -> {
                        throw new RuntimeException(
                                "A checkpoint already exists for this entity on " + newDate);
                    });
            checkpoint.setCheckpointDate(newDate);
        }

        if (request.getDescription() != null) {
            if (request.getDescription().isBlank()) {
                throw new RuntimeException("description must not be blank");
            }
            checkpoint.setDescription(request.getDescription());
        }

        Checkpoint saved = checkpointRepository.save(checkpoint);
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long checkpointId) {
        Checkpoint checkpoint = checkpointRepository.findById(checkpointId)
                .orElseThrow(() -> new RuntimeException(
                        "Checkpoint not found with id " + checkpointId));
        entityAccessService.assertOwnedByCurrentUser(checkpoint.getManagedEntity().getId());
        checkpointRepository.delete(checkpoint);
    }

    private CheckpointResponse toResponse(Checkpoint c) {
        CheckpointResponse r = new CheckpointResponse();
        r.setId(c.getId());
        r.setEntityId(c.getManagedEntity().getId());
        r.setEntityName(c.getManagedEntity().getName());
        r.setCheckpointDate(c.getCheckpointDate());
        r.setDescription(c.getDescription());
        return r;
    }
}
