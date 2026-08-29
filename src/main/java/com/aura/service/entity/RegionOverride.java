package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One human correction of a mention's {@code predicted_region}. Same append-only overlay design as
 * {@link TopicCategoryOverride} — see its javadoc for why this never writes to the upstream
 * ingestion tables directly.
 */
@Entity
@Table(name = "region_overrides")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegionOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mention_id", nullable = false)
    private Long mentionId;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "previous_category")
    private String previousCategory;

    @Column(name = "new_category", nullable = false)
    private String newCategory;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
