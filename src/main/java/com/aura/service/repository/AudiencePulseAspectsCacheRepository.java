package com.aura.service.repository;

import com.aura.service.entity.AudiencePulseAspectsCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AudiencePulseAspectsCacheRepository extends JpaRepository<AudiencePulseAspectsCache, Long> {
    Optional<AudiencePulseAspectsCache> findByEntityId(Long entityId);
}
