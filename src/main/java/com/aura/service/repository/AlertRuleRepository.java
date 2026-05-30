package com.aura.service.repository;

import com.aura.service.entity.AlertRule;
import com.aura.service.entity.SentimentAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findByUserId(Long userId);

    Optional<AlertRule> findByIdAndUserId(Long id, Long userId);

    /**
     * Enabled rules of the given kind that apply to an entity: either scoped to
     * that entity, or wildcard rules (null entityId) that apply to all entities.
     */
    @Query("SELECT r FROM AlertRule r WHERE r.kind = :kind AND r.enabled = true " +
            "AND (r.entityId = :entityId OR r.entityId IS NULL)")
    List<AlertRule> findApplicable(@Param("kind") SentimentAlert.Kind kind,
                                   @Param("entityId") Long entityId);
}
