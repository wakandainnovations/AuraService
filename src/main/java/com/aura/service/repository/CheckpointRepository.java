package com.aura.service.repository;

import com.aura.service.entity.Checkpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CheckpointRepository extends JpaRepository<Checkpoint, Long> {

    List<Checkpoint> findByManagedEntityIdOrderByCheckpointDateAsc(Long entityId);

    List<Checkpoint> findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
            Long entityId, LocalDate start, LocalDate end);

    Optional<Checkpoint> findByManagedEntityIdAndCheckpointDate(Long entityId, LocalDate date);

    void deleteByManagedEntityId(Long entityId);
}
