package com.aura.service.repository;

import com.aura.service.entity.SituationRecommendationCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SituationRecommendationCacheRepository extends JpaRepository<SituationRecommendationCache, Long> {
    Optional<SituationRecommendationCache> findByEntityId(Long entityId);
}
