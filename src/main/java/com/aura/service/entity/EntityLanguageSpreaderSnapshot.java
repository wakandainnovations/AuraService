package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Populated by TopSpreaderLanguageSyncService's periodic (every 2 days) sweep over AuraMath's
// top-50-spreaders endpoint, once per (entity, language) pair this entity actually markets in - i.e.
// has a tracked EntityKeyword tagged with that language (see TopSpreaderLanguageSyncService for how
// the language grouping works). Read by RecommendedActionCandidateServiceImpl's top-spreader-gap
// candidate rather than paying for a live AuraMath round-trip per candidate generation.
@Entity
@Table(
        name = "entity_language_spreader_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_entity_language_spreader_snapshot",
                columnNames = {"entity_id", "language"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityLanguageSpreaderSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "language", nullable = false)
    private String language;

    @Column(name = "spreader_count", nullable = false)
    private int spreaderCount;

    // JSON array of TopSpreaderLookupService.SpreaderProfile, deduped across every keyword tagged with
    // this language - kept as full profiles (not just a count) so platform/profile-link data survives
    // for the "reach out to these spreaders" candidate.
    @Column(name = "spreaders_json", columnDefinition = "TEXT", nullable = false)
    private String spreadersJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
