package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted result of the last "AI Summary" / "Today's Highlights" generation for a
 * {@link ManagedEntity}, refreshed on a schedule so the dashboard endpoints can serve a cached
 * row instead of waiting on an LLM call. See CommandCenterSummaryService.
 */
@Entity
@Table(
        name = "command_center_summary_cache",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_command_center_summary_cache_entity",
                columnNames = "entity_id"
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommandCenterSummaryCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "entity_name", nullable = false)
    private String entityName;

    @Column(name = "summary", columnDefinition = "TEXT", nullable = false)
    private String summary;

    // Serialized List<HighlightItem> (JSON) - stored as raw text rather than an @ElementCollection
    // table since it's only ever read back out as a whole and never queried by field.
    @Column(name = "highlights_json", columnDefinition = "TEXT", nullable = false)
    private String highlightsJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
