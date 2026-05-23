package com.aura.service.repository;

import com.aura.service.entity.ReplyDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReplyDraftRepository extends JpaRepository<ReplyDraft, Long> {
}
