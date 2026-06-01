package com.aura.service.repository;

import com.aura.service.entity.ReplyDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ReplyDraftRepository extends JpaRepository<ReplyDraft, Long> {
    List<ReplyDraft> findByMentionId(Long mentionId);

    List<ReplyDraft> findByMentionIdIn(Collection<Long> mentionIds);

    long countByUserIdAndStatus(Long userId, ReplyDraft.Status status);
}
