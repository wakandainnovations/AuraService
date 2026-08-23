package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted result of the last full marketing-report assembly ({@code EntityMarketingReportResponse},
 * serialized whole as JSON) for one {@link ManagedEntity} at one (period, windowDays) combination,
 * refreshed on a schedule so {@code GET /api/entities/{entityType}/{id}/marketing-report} can serve a
 * cached row instead of paying for its ~15 downstream calls (and an LLM call) on every request. See
 * {@code EntityMarketingReportService}.
 */
@Entity
@Table(
        name = "entity_marketing_report_cache",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_entity_marketing_report_cache_entity_period_window",
                columnNames = {"entity_id", "period", "window_days"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityMarketingReportCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "period", nullable = false)
    private String period;

    @Column(name = "window_days", nullable = false)
    private int windowDays;

    // Serialized EntityMarketingReportResponse (JSON) - stored as raw text rather than modeled as
    // columns since the report's shape is wide, nested, and evolves with every new section added to
    // the report; it's only ever read back out as a whole, never queried by field.
    @Column(name = "report_json", columnDefinition = "TEXT", nullable = false)
    private String reportJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
