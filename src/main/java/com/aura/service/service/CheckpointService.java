package com.aura.service.service;

import com.aura.service.dto.CheckpointResponse;
import com.aura.service.dto.CreateCheckpointRequest;
import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CheckpointService {

    private final CheckpointRepository checkpointRepository;
    private final ManagedEntityRepository entityRepository;

    @Transactional
    public CheckpointResponse create(CreateCheckpointRequest request) {
        ManagedEntity entity = entityRepository.findById(request.getEntityId())
                .orElseThrow(() -> new RuntimeException(
                        "ManagedEntity not found with id " + request.getEntityId()));

        Checkpoint checkpoint = Checkpoint.builder()
                .managedEntity(entity)
                .checkpointDate(request.getCheckpointDate())
                .description(request.getDescription())
                .build();

        Checkpoint saved = checkpointRepository.save(checkpoint);
        return toResponse(saved);
    }

    public List<CheckpointResponse> listByEntity(Long entityId) {
        return checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(entityId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void delete(Long checkpointId) {
        Checkpoint checkpoint = checkpointRepository.findById(checkpointId)
                .orElseThrow(() -> new RuntimeException(
                        "Checkpoint not found with id " + checkpointId));
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
