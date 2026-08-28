package com.aura.service.repository;

import com.aura.service.entity.ReviewAspectOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewAspectOverrideRepository extends JpaRepository<ReviewAspectOverride, Long> {
    List<ReviewAspectOverride> findByMentionId(Long mentionId);

    void deleteByMentionId(Long mentionId);
}
