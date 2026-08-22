package com.aura.service.repository;

import com.aura.service.entity.EntityViralSeedSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EntityViralSeedSnapshotRepository extends JpaRepository<EntityViralSeedSnapshot, Long> {

    Optional<EntityViralSeedSnapshot> findByEntityId(Long entityId);

    // Used by the cumulative-view-count-gap candidate to look up comparable movies' viral-seed
    // snapshots.
    List<EntityViralSeedSnapshot> findByEntityIdIn(List<Long> entityIds);
}
