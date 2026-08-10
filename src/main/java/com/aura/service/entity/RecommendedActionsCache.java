package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
        name = "recommended_actions_cache",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_recommended_actions_cache_entity",
                columnNames = "entity_id"
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedActionsCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "entity_name", nullable = false)
    private String entityName;

    @Column(name = "actions_json", columnDefinition = "TEXT", nullable = false)
    private String actionsJson;

    @Column(name = "days_to_release_at_generation", nullable = false)
    private int daysToReleaseAtGeneration;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
