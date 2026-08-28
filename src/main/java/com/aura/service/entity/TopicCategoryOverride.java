package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One human correction of a mention's {@code topic_category}. Unlike {@link ReviewAspectOverride},
 * this never writes to the upstream ingestion tables ({@code x_posts}/{@code youtube_comments}/
 * {@code reddit_posts}/{@code instagram_posts}) — that data is populated by a pipeline outside this
 * codebase, and a direct write here could be silently clobbered on the next upstream sync, or
 * corrupt data other consumers of those tables rely on. Instead this is an append-only overlay:
 * every read path that reports {@code topic_category} (the breakdown and the drill-down filter on
 * {@code GET /api/dashboard/{entityId}/mentions}) resolves the *latest* override row for a mention
 * (by {@code created_at}) ahead of the raw upstream column, falling back to upstream when no
 * override exists. {@code category} is a plain string, not a Java enum, matching the upstream
 * taxonomy this overlays (AuraService doesn't own or fully enumerate it).
 */
@Entity
@Table(name = "topic_category_overrides")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicCategoryOverride {

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
