package com.aura.service.entity;

import com.aura.service.enums.ReviewAspectCategory;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One human correction of {@link Mention#getReviewAspectCategory()} — the audit trail that lets a
 * marketing team member who spots a bad classification via the drill-down filters on
 * {@code GET /api/dashboard/{entityId}/mentions} fix it, and lets anyone later see that it was
 * fixed, by whom, and what the LLM originally said. {@code previousCategory} is nullable: a post
 * can be manually classified before the background sweep ever reaches it.
 */
@Entity
@Table(name = "review_aspect_overrides")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewAspectOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mention_id", nullable = false)
    private Long mentionId;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_category")
    private ReviewAspectCategory previousCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_category", nullable = false)
    private ReviewAspectCategory newCategory;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
