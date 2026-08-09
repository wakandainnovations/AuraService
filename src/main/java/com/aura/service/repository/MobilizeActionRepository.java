package com.aura.service.repository;

import com.aura.service.entity.MobilizeAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MobilizeActionRepository extends JpaRepository<MobilizeAction, Long> {
    List<MobilizeAction> findByMentionId(Long mentionId);

    List<MobilizeAction> findByUserId(Long userId);

    // Historical mobilize events across a set of comparable entities (same budget tier + language/
    // genre), used by RecommendedActionCandidateServiceImpl to measure the mention-volume lift those
    // events correlated with.
    List<MobilizeAction> findByEntityIdIn(List<Long> entityIds);

    void deleteByMentionId(Long mentionId);
}
