package com.aura.service.repository;

import com.aura.service.entity.RegionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegionOverrideRepository extends JpaRepository<RegionOverride, Long> {
    List<RegionOverride> findByMentionId(Long mentionId);

    void deleteByMentionId(Long mentionId);
}
