package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin view of one tracked release. {@code status} is derived from today (IST) vs the tracking
 * window: UPCOMING (window not started), TRACKING (being scanned now), ENDED, or EXCLUDED.
 * {@code collected} is the number of items stored so far per platform (x, instagram, reddit,
 * youtubeComments, youtubeVideos) - the proxy for how much collection budget a movie uses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackedReleaseResponse {
    private Long id;
    private Integer tmdbId;
    private String entityName;
    private String title;
    private String originalTitle;
    private String language;
    private LocalDate releaseDate;
    private LocalDate trackFrom;
    private LocalDate trackUntil;
    private List<String> keywords;
    private String source;
    private Double popularity;
    private String posterUrl;
    private boolean excluded;
    private String excludedReason;
    private Instant excludedAt;
    private String status;
    private Map<String, Long> collected;
    private long collectedTotal;
}
