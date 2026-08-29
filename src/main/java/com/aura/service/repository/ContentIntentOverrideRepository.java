package com.aura.service.repository;

import com.aura.service.entity.ContentIntentOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContentIntentOverrideRepository extends JpaRepository<ContentIntentOverride, Long> {
    List<ContentIntentOverride> findByMentionId(Long mentionId);

    void deleteByMentionId(Long mentionId);
}
