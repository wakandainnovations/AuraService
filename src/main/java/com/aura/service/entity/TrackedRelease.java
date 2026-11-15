package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A movie the social-media collector (AuraSocialMediaService) scans X/YouTube/Instagram/Reddit for.
 * Rows are inserted automatically for every India-origin TMDB release (source TMDB) or by an admin
 * (source MANUAL), and scanned only while today is within [trackFrom, trackUntil] and the row is
 * not excluded. Canonical schema: AuraSocialMediaService db/migration/V7__add_tracked_releases.sql.
 *
 * <p>{@code entityName} is the value stored in x_posts.entity / instagram_posts.entity / etc., so
 * it is fixed once the row exists.
 */
@Entity
@Table(name = "tracked_releases")
@Data
@NoArgsConstructor
public class TrackedRelease {

    public static final String SOURCE_TMDB = "TMDB";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_id", unique = true)
    private Integer tmdbId;

    @Column(name = "entity_name", nullable = false, unique = true, columnDefinition = "TEXT")
    private String entityName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "original_title", columnDefinition = "TEXT")
    private String originalTitle;

    @Column(columnDefinition = "TEXT")
    private String language;

    @Column(name = "release_date", nullable = false)
    private LocalDate releaseDate;

    @Column(name = "track_from", nullable = false)
    private LocalDate trackFrom;

    @Column(name = "track_until", nullable = false)
    private LocalDate trackUntil;

    /** Comma-separated search keywords; the scanners OR them per platform. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String keywords;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String category = "media.movie";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String source = SOURCE_TMDB;

    private Double popularity;

    @Column(name = "poster_path", columnDefinition = "TEXT")
    private String posterPath;

    @Column(nullable = false)
    private boolean excluded;

    @Column(name = "excluded_reason", columnDefinition = "TEXT")
    private String excludedReason;

    @Column(name = "excluded_at")
    private Instant excludedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
