package com.aura.service.repository;

import com.aura.service.entity.ReplyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReplyTemplateRepository extends JpaRepository<ReplyTemplate, Long> {
    List<ReplyTemplate> findByUserIdOrderByCreatedAtDesc(Long userId);
}
