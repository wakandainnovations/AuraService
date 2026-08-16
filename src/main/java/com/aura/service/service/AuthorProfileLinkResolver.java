package com.aura.service.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Extracts a real per-author profile URL from an AuraMath author JSON element, when present. AuraMath
 * does not use one consistent shape for this across endpoints (confirmed against its own source):
 * <ul>
 *     <li>{@code top-50-spreaders} elements carry a flat {@code profile_url} string field directly
 *         (populated by AuraMath's own {@code attachProfileLinks} enrichment step; {@code null} when an
 *         author hasn't been enriched yet) - {@link #extractProfileUrl} reads this first.</li>
 *     <li>Entity-report {@code topAdvocates} elements (a different endpoint from the three above) carry
 *         no flat field, only a nested {@code platform_handles: {primary_platform, by_platform: {<platform>:
 *         {profile_url}}}} tree - {@link #extractProfileUrl} falls back to parsing this when there's no
 *         flat field.</li>
 *     <li>{@code viral-seeds} elements carry neither of the above - the link lives one level down at
 *         {@code outreachHandle.profile_url} - see {@link #extractOutreachProfileUrl}.</li>
 *     <li>{@code movie-buffs} elements carry a flat, already-resolved {@code profileUrl} (camelCase,
 *         distinct from top-50-spreaders' snake_case {@code profile_url}) - null when AuraMath has no
 *         {@code marketing_target_profiles} row for that author yet - see
 *         {@link #extractMovieBuffProfileUrl}.</li>
 * </ul>
 * Shared so {@link TopSpreaderLookupService}, {@link MovieBuffLookupServiceImpl}, and
 * {@link ViralSeedLookupServiceImpl} don't each re-derive this. Never fabricates a URL from a bare
 * handle - returns null whenever the element carries no real link data.
 */
final class AuthorProfileLinkResolver {

    private AuthorProfileLinkResolver() {
    }

    /** For top-50-spreaders (flat {@code profile_url}) and entity-report advocates (nested
     *  {@code platform_handles}) - see class doc for which endpoints use which shape. */
    static String extractProfileUrl(JsonNode element) {
        if (element == null || !element.isObject()) {
            return null;
        }
        String flat = textField(element, "profile_url");
        if (flat != null && !flat.isBlank()) {
            return flat;
        }
        return extractFromPlatformHandles(element);
    }

    /** For viral-seeds, whose profile link lives at {@code element.outreachHandle.profile_url} rather
     *  than a flat field or {@code platform_handles}. */
    static String extractOutreachProfileUrl(JsonNode element) {
        if (element == null || !element.isObject()) {
            return null;
        }
        JsonNode outreachHandle = element.get("outreachHandle");
        if (outreachHandle == null || !outreachHandle.isObject()) {
            return null;
        }
        String url = textField(outreachHandle, "profile_url");
        return (url != null && !url.isBlank()) ? url : null;
    }

    /** For movie-buffs, whose profile link is a flat, camelCase {@code profileUrl} field - already
     *  resolved server-side by AuraMath from its own {@code platform_handles} join, unlike
     *  top-50-spreaders' snake_case {@code profile_url} handled by {@link #extractProfileUrl}. */
    static String extractMovieBuffProfileUrl(JsonNode element) {
        if (element == null || !element.isObject()) {
            return null;
        }
        String url = textField(element, "profileUrl");
        return (url != null && !url.isBlank()) ? url : null;
    }

    private static String extractFromPlatformHandles(JsonNode element) {
        JsonNode handles = element.get("platform_handles");
        if (handles == null || !handles.isObject()) {
            return null;
        }
        JsonNode byPlatform = handles.get("by_platform");
        if (byPlatform == null || !byPlatform.isObject()) {
            byPlatform = handles;
        }
        String primary = textField(handles, "primary_platform");
        JsonNode selected = primary != null ? byPlatform.get(primary) : null;
        if (selected == null && byPlatform.fieldNames().hasNext()) {
            selected = byPlatform.get(byPlatform.fieldNames().next());
        }
        if (selected != null && selected.isObject()) {
            String url = textField(selected, "profile_url");
            return (url != null && !url.isBlank()) ? url : null;
        }
        if (selected != null && selected.isValueNode()) {
            // Legacy flat shape: only trust it as a profile URL if it actually looks like one -
            // a bare handle string here (e.g. "@cinemaSage") is not a link to fabricate one from.
            String value = selected.asText();
            return (value != null && (value.startsWith("http://") || value.startsWith("https://"))) ? value : null;
        }
        return null;
    }

    private static String textField(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isTextual()) ? v.asText() : null;
    }
}
