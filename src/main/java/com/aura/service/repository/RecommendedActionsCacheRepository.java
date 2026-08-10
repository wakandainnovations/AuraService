package com.aura.service.repository;

import com.aura.service.entity.RecommendedActionsCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RecommendedActionsCacheRepository extends JpaRepository<RecommendedActionsCache, Long> {
    Optional<RecommendedActionsCache> findByEntityId(Long entityId);
}
