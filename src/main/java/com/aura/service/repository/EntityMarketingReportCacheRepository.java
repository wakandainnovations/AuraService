package com.aura.service.repository;

import com.aura.service.entity.EntityMarketingReportCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EntityMarketingReportCacheRepository extends JpaRepository<EntityMarketingReportCache, Long> {
    Optional<EntityMarketingReportCache> findByEntityIdAndPeriodAndWindowDays(
            Long entityId, String period, int windowDays);
}
