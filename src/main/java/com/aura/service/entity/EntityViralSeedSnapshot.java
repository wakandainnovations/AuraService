package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Populated by ViralSeedSyncService's periodic (every 2 days) sweep over AuraMath's keyword-scoped
// viral-seeds endpoint, once per MOVIE entity, deduped across every tracked EntityKeyword (see
// ViralSeedSyncService for why this isn't split per language like EntityLanguageSpreaderSnapshot is).
// Read by RecommendedActionCandidateServiceImpl's cumulative-view-count-gap candidate rather than
// paying for a live AuraMath round-trip per candidate generation.
@Entity
@Table(
        name = "entity_viral_seed_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_entity_viral_seed_snapshot_entity",
                columnNames = {"entity_id"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityViralSeedSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "seed_count", nullable = false)
    private int seedCount;

    // JSON array of ViralSeedLookupService.ViralSeed, deduped across every tracked keyword for this
    // entity - kept as full seed records (not just a count) so platform/profile-link data survives for
    // the outreach candidate.
    @Column(name = "seeds_json", columnDefinition = "TEXT", nullable = false)
    private String seedsJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
