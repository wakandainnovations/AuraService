package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted cache row for {@code TopSpreaderInsightsService}'s LLM-generated top-spreader collaboration
 * insights, keyed by the same dimensions that shape what's actually sent to the LLM - {@code language}
 * (normalized: {@code null}/blank request language is stored as {@code ""}, matching
 * {@code TopSpreaderContentService}'s own no-filter convention), {@code spreaderLimit}, and
 * {@code postsPerSpreader}. {@code generatedAt} drives the 24h staleness check the service uses to
 * decide whether to serve this row as-is or serve it while kicking off a background regeneration.
 */
@Entity
@Table(
        name = "top_spreader_insights_cache",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_top_spreader_insights_cache_key",
                columnNames = {"entity_id", "language", "spreader_limit", "posts_per_spreader"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopSpreaderInsightsCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "language", nullable = false)
    private String language;

    @Column(name = "spreader_limit", nullable = false)
    private int spreaderLimit;

    @Column(name = "posts_per_spreader", nullable = false)
    private int postsPerSpreader;

    @Column(name = "summary", columnDefinition = "TEXT", nullable = false)
    private String summary;

    @Column(name = "actions_json", columnDefinition = "TEXT", nullable = false)
    private String actionsJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
