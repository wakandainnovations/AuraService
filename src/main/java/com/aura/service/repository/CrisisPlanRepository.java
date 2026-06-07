package com.aura.service.repository;

import com.aura.service.entity.CrisisPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CrisisPlanRepository extends JpaRepository<CrisisPlan, Long> {
    List<CrisisPlan> findByMentionId(Long mentionId);

    List<CrisisPlan> findByMentionIdIn(Collection<Long> mentionIds);

    void deleteByMentionId(Long mentionId);

    List<CrisisPlan> findByEntityId(Long entityId);

    List<CrisisPlan> findByCreatedBy(Long createdBy);
}
