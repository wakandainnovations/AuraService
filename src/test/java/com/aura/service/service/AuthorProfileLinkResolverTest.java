package com.aura.service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in {@link AuthorProfileLinkResolver} against the real, non-uniform AuraMath response shapes
 * confirmed directly against AuraMath's own source (see its {@code TopSpreadersController},
 * {@code ViralSeedController}, {@code EntityMarketingService}) - a prior version of this resolver only
 * checked the {@code platform_handles} shape, which silently produced null for every viral-seed account
 * because that endpoint's link actually lives at {@code outreachHandle.profile_url}.
 */
class AuthorProfileLinkResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    // ==================== extractProfileUrl: top-50-spreaders' flat field ====================

    @Test
    void extractProfileUrl_readsFlatProfileUrlField() throws Exception {
        JsonNode element = json("""
                {"author": "alice", "profile_url": "https://x.com/alice"}
                """);
        assertThat(AuthorProfileLinkResolver.extractProfileUrl(element)).isEqualTo("https://x.com/alice");
    }

    @Test
    void extractProfileUrl_nullFlatFieldMeansAuthorNotYetEnriched() throws Exception {
        JsonNode element = json("""
                {"author": "alice", "profile_url": null}
                """);
        assertThat(AuthorProfileLinkResolver.extractProfileUrl(element)).isNull();
    }

    // ==================== extractProfileUrl: entity-report advocates' nested platform_handles ====================

    @Test
    void extractProfileUrl_fallsBackToPlatformHandlesWhenNoFlatField() throws Exception {
        JsonNode element = json("""
                {
                  "global_user_id": "u-101",
                  "platform_handles": {
                    "primary_platform": "x",
                    "by_platform": {"x": {"profile_url": "https://twitter.com/cinemaSage"}}
                  }
                }
                """);
        assertThat(AuthorProfileLinkResolver.extractProfileUrl(element)).isEqualTo("https://twitter.com/cinemaSage");
    }

    @Test
    void extractProfileUrl_missingPlatformHandlesReturnsNull() throws Exception {
        JsonNode element = json("""
                {"author": "alice"}
                """);
        assertThat(AuthorProfileLinkResolver.extractProfileUrl(element)).isNull();
    }

    // ==================== extractOutreachProfileUrl: viral-seeds' nested outreachHandle ====================

    @Test
    void extractOutreachProfileUrl_readsNestedOutreachHandleProfileUrl() throws Exception {
        JsonNode element = json("""
                {
                  "author": "u1",
                  "primaryPlatform": "instagram",
                  "outreachHandle": {"platform": "instagram", "profile_url": "https://instagram.com/u1", "permalink": "https://instagram.com/p/abc"}
                }
                """);
        assertThat(AuthorProfileLinkResolver.extractOutreachProfileUrl(element)).isEqualTo("https://instagram.com/u1");
    }

    @Test
    void extractOutreachProfileUrl_missingOutreachHandleReturnsNull() throws Exception {
        JsonNode element = json("""
                {"author": "u2", "primaryPlatform": "x"}
                """);
        assertThat(AuthorProfileLinkResolver.extractOutreachProfileUrl(element)).isNull();
    }

    @Test
    void extractOutreachProfileUrl_nullOutreachHandleReturnsNull() throws Exception {
        JsonNode element = json("""
                {"author": "u2", "primaryPlatform": "x", "outreachHandle": null}
                """);
        assertThat(AuthorProfileLinkResolver.extractOutreachProfileUrl(element)).isNull();
    }

    // ==================== movie-buffs: genuinely no link data at all ====================

    @Test
    void extractProfileUrl_movieBuffShapeHasNoLinkData() throws Exception {
        JsonNode element = json("""
                {"author": "u3", "influenceTier": "TIER_1"}
                """);
        assertThat(AuthorProfileLinkResolver.extractProfileUrl(element)).isNull();
        assertThat(AuthorProfileLinkResolver.extractOutreachProfileUrl(element)).isNull();
    }

    // ==================== Never fabricate ====================

    @Test
    void extractProfileUrl_bareHandleInLegacyFlatShapeIsNotTreatedAsUrl() throws Exception {
        JsonNode element = json("""
                {
                  "platform_handles": {
                    "primary_platform": "x",
                    "by_platform": {"x": "@cinemaSage"}
                  }
                }
                """);
        assertThat(AuthorProfileLinkResolver.extractProfileUrl(element)).isNull();
    }
}
