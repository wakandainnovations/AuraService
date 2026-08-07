package com.aura.service.service;

import java.util.Map;

/**
 * Matches an entity name to a poster file in the configured {@code entity.images.base-path}
 * directory, by normalized name (case/whitespace/punctuation insensitive — e.g. "GD Naidu"
 * matches {@code GDNaidu.jpeg}). Shared by {@link com.aura.service.config.EntityImageBackfill}
 * (bulk match on startup) and {@link EntityService} (re-match when a name changes), so the two
 * never disagree on what counts as a match.
 *
 * <p>Defined as an interface (mirroring {@link EntityAccessService} / {@link LicenseService}) so
 * callers can mock it with an interface rather than a concrete class in unit tests.
 */
public interface EntityImageMatcher {

    /** The matching filename for {@code entityName}, or null if no image file matches it. */
    String matchFile(String entityName);

    Map<String, String> listImageFilesByNormalizedName();

    /** Lowercases and strips everything but letters/digits, so naming variants (spaces, underscores,
     *  punctuation like the colon in "Balan: The Boy") all collapse to the same key. */
    String normalize(String name);
}
