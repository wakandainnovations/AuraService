package com.aura.service.service;

import com.aura.service.dto.CreateTrackedReleaseRequest;
import com.aura.service.dto.TrackedReleaseResponse;
import com.aura.service.dto.UpdateTrackedReleaseRequest;

import java.util.List;

/**
 * Admin management of the movies the social-media collector scans (tracked_releases). Discovery of
 * TMDB releases lives in AuraSocialMediaService; this service only reviews and edits the list.
 */
public interface TrackedReleaseService {

    /** @param scope "current" (default: upcoming + tracking, not excluded), "excluded", "ended" or "all" */
    List<TrackedReleaseResponse> list(String scope);

    TrackedReleaseResponse create(CreateTrackedReleaseRequest request);

    TrackedReleaseResponse update(Long id, UpdateTrackedReleaseRequest request);

    /**
     * Makes the release a user-facing movie (managed entity) owned by {@code ownerId} (null = the
     * calling admin): same name - so it shows the posts already collected for it - plus release
     * date, language/industry and keywords. Fails if that user already has a movie of that name.
     */
    TrackedReleaseResponse addToPlatform(Long id, Long ownerId);

    /** MANUAL rows only - a deleted TMDB row would just be re-discovered, so those are excluded instead. */
    void delete(Long id);
}
