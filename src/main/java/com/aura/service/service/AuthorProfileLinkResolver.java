package com.aura.service.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Extracts a real per-author profile URL/platform from an AuraMath author JSON element's
 * {@code platform_handles} object, when present - the same {@code primary_platform} /
 * {@code by_platform.<platform>.profile_url} shape {@link EntityMarketingReportPdfService#advocateHandle}
 * already reads off AuraMath's entity-report "topAdvocates". Shared here so
 * {@link TopSpreaderLookupService}, {@link MovieBuffLookupServiceImpl}, and
 * {@link ViralSeedLookupServiceImpl} don't each re-derive it. Returns null for either field (never
 * fabricates a URL from a bare handle) when the element carries no such data - not every AuraMath
 * endpoint that returns an author necessarily includes it.
 */
final class AuthorProfileLinkResolver {

    private AuthorProfileLinkResolver() {
    }

    static String extractPlatform(JsonNode element) {
        JsonNode handles = platformHandles(element);
        if (handles == null) {
            return null;
        }
        String primary = textField(handles, "primary_platform");
        return (primary != null && !primary.isBlank()) ? primary : null;
    }

    static String extractProfileUrl(JsonNode element) {
        JsonNode handles = platformHandles(element);
        if (handles == null) {
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

    private static JsonNode platformHandles(JsonNode element) {
        if (element == null || !element.isObject()) {
            return null;
        }
        JsonNode handles = element.get("platform_handles");
        return (handles != null && handles.isObject()) ? handles : null;
    }

    private static String textField(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isTextual()) ? v.asText() : null;
    }
}
