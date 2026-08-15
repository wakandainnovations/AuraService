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

    // ==================== Verbatim elements captured live from a running AuraMath instance
    // (localhost:8081, /api/marketing/{top-50-spreaders,movie-buffs,viral-seeds}/toxic) - real payload
    // shape, not a synthetic approximation, so a future AuraMath response-shape change that silently
    // breaks extraction again shows up here. ====================

    @Test
    void extractProfileUrl_realTopSpreadersElement() throws Exception {
        JsonNode element = json("""
                {
                  "author": "𝙍𝙤𝙘𝙠𝙞𝙣𝙜 𝘼𝙨𝙝𝙪👑",
                  "viral_potential_score": 1223.1108224979314,
                  "alpha": 0.6528524628350423,
                  "engagement_count": 740.0,
                  "total_likes": 740,
                  "total_comments": 0,
                  "total_views": 10179,
                  "engagement_rate": 0.07269869338834856,
                  "average_sentiment_score": 90.4,
                  "platform_handles": {
                    "by_platform": {
                      "x": {
                        "post_count": 1.0,
                        "profile_url": "https://twitter.com/Dudey212530",
                        "total_likes": 84.0,
                        "total_views": 1647.0,
                        "total_comments": 0.0,
                        "sample_post_url": "https://twitter.com/Dudey212530/status/2084626867177541761",
                        "avg_engagement_per_post": 84.0
                      }
                    },
                    "primary_platform": "x"
                  },
                  "profile_url": "https://twitter.com/Dudey212530"
                }
                """);
        assertThat(AuthorProfileLinkResolver.extractProfileUrl(element)).isEqualTo("https://twitter.com/Dudey212530");
    }

    @Test
    void extractProfileUrl_realMovieBuffsElementHasNoLinkData() throws Exception {
        JsonNode element = json("""
                {
                  "author": "𝗥𝗞 2.0 ᴿᵃʸᵃ",
                  "audienceClassification": "Movie Buff",
                  "influenceTier": "Viral Node",
                  "postingStyle": "Power Burst Poster",
                  "dominantTone": "positive",
                  "primaryPlatform": "x",
                  "branchingRatio": 1.0,
                  "totalPosts": 11,
                  "keywordPostCount": 8,
                  "keywordEngagement": 2
                }
                """);
        assertThat(AuthorProfileLinkResolver.extractProfileUrl(element)).isNull();
    }

    @Test
    void extractOutreachProfileUrl_realViralSeedsElement() throws Exception {
        JsonNode element = json("""
                {
                  "rank": 1,
                  "author": "Honest Review",
                  "seedScore": 5.2155,
                  "hawkesAlpha": 2.9411469,
                  "moiScore": 0.029839732,
                  "tribe": "Tribe_2",
                  "primaryPlatform": "x",
                  "outreachHandle": {
                    "platform": "x",
                    "profile_url": "https://twitter.com/honestreview01",
                    "permalink": "https://twitter.com/honestreview01/status/2071239626090766468"
                  },
                  "reachSignals": {
                    "x_views_count": 27587,
                    "instagram_like_count": 0,
                    "reddit_score": 0,
                    "youtube_comment_count": 0
                  }
                }
                """);
        assertThat(AuthorProfileLinkResolver.extractOutreachProfileUrl(element))
                .isEqualTo("https://twitter.com/honestreview01");
    }
}
