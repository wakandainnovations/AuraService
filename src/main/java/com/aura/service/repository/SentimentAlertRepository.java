package com.aura.service.repository;

import com.aura.service.entity.SentimentAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface SentimentAlertRepository extends JpaRepository<SentimentAlert, Long> {

    boolean existsByManagedEntityIdAndStatusAndTriggeredAtAfter(
            Long managedEntityId,
            SentimentAlert.Status status,
            Instant triggeredAfter
    );
}
