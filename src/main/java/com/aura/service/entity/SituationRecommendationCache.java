package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persists the last generated {@code SituationRecommendationResponse} (as JSON) for one entity, so the
 * "Social Buzz Situation" panel doesn't re-run the LLM on every page load. One row per entity id, kept
 * fresh by {@link com.aura.service.service.SituationRecommendationService#CACHE_TTL} or an explicit
 * {@code refresh=true}, unlike {@link RecommendedActionsCache}'s scheduler-driven 24h cycle - this
 * feature reacts to a same-day sentiment burst, so it recomputes synchronously on a much shorter TTL
 * instead of waiting on a background sweep.
 */
@Entity
@Table(
        name = "situation_recommendation_cache",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_situation_recommendation_cache_entity",
                columnNames = "entity_id"
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SituationRecommendationCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "response_json", columnDefinition = "TEXT", nullable = false)
    private String responseJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
