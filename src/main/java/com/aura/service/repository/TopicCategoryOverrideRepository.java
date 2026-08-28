package com.aura.service.repository;

import com.aura.service.entity.TopicCategoryOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TopicCategoryOverrideRepository extends JpaRepository<TopicCategoryOverride, Long> {
    List<TopicCategoryOverride> findByMentionId(Long mentionId);

    void deleteByMentionId(Long mentionId);
}
