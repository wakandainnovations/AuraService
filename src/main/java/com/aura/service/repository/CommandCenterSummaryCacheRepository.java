package com.aura.service.repository;

import com.aura.service.entity.CommandCenterSummaryCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CommandCenterSummaryCacheRepository extends JpaRepository<CommandCenterSummaryCache, Long> {
    Optional<CommandCenterSummaryCache> findByEntityId(Long entityId);
}
