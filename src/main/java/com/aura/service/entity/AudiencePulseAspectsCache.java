package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted result of the last "People Love" / "People Concerned About" aspect-driver ranking for a
 * {@link ManagedEntity}, refreshed on a schedule so the Audience Pulse panel endpoint can serve a
 * cached row instead of calling AuraMath on every request. See AudiencePulseAspectsService.
 */
@Entity
@Table(
        name = "audience_pulse_aspects_cache",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_audience_pulse_aspects_cache_entity",
                columnNames = "entity_id"
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AudiencePulseAspectsCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "entity_name", nullable = false)
    private String entityName;

    // Serialized List<String> (JSON) - stored as raw text rather than an @ElementCollection table
    // since it's only ever read back out as a whole and never queried by field.
    @Column(name = "people_love_json", columnDefinition = "TEXT", nullable = false)
    private String peopleLoveJson;

    @Column(name = "people_concerned_json", columnDefinition = "TEXT", nullable = false)
    private String peopleConcernedJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
