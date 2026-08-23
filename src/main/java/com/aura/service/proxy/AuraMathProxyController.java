package com.aura.service.proxy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1")
@Tag(name = "AuraMath Proxy", description = "Thin proxy over the upstream AuraMath service")
public class AuraMathProxyController {

    private static final Set<String> VALID_SPREADER_PLATFORMS = Set.of("x", "youtube", "reddit", "instagram");

    private final AuraMathProxyService proxy;
    private final AuraMathProperties props;

    public AuraMathProxyController(AuraMathProxyService proxy, AuraMathProperties props) {
        this.proxy = proxy;
        this.props = props;
    }

    @Operation(summary = "Get viral seeds for a keyword (cacheable)")
    @GetMapping(value = "/viral-seeds", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> viralSeeds(@RequestParam("keyword") String keyword) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("keyword", keyword);
        return proxy.forwardGet(
                "/v1/viral-seeds",
                "/api/marketing/viral-seeds",
                q,
                true,
                null
        );
    }

    @Operation(summary = "Get aspect drivers for a keyword (cacheable)")
    @GetMapping(value = "/aspect-drivers/{keyword}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> aspectDrivers(@PathVariable("keyword") String keyword) {
        return proxy.forwardGet(
                "/v1/aspect-drivers/{keyword}",
                "/api/marketing/aspect-drivers/" + encodeSegment(keyword),
                null,
                true,
                null
        );
    }

    @Operation(summary = "Get aspect drivers aggregated across an entity's tracked keywords (cacheable)")
    @GetMapping(value = "/aspect-drivers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> aspectDriversByEntity(@RequestParam("entityId") String entityId) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("entityId", entityId);
        return proxy.forwardGet(
                "/v1/aspect-drivers",
                "/api/marketing/aspect-drivers",
                q,
                true,
                null
        );
    }

    @Operation(summary = "Get top spreaders for a keyword, optionally filtered by platform (cacheable)")
    @GetMapping(value = "/top-spreaders/{keyword}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> topSpreaders(
            @PathVariable("keyword") String keyword,
            @RequestParam(value = "platform", required = false) String platform
    ) {
        Map<String, Object> q = null;
        if (platform != null) {
            String normalized = platform.toLowerCase();
            if (!VALID_SPREADER_PLATFORMS.contains(normalized)) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"platform must be one of x, youtube, reddit, instagram\"}");
            }
            q = new LinkedHashMap<>();
            q.put("platform", normalized);
        }
        return proxy.forwardGet(
                "/v1/top-spreaders/{keyword}",
                "/api/marketing/top-50-spreaders/" + encodeSegment(keyword),
                q,
                true,
                null
        );
    }

    @Operation(summary = "Find lookalike authors given a seed author")
    @PostMapping(value = "/find-lookalikes",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> findLookalikes(@RequestBody(required = false) FindLookalikesRequest body) {
        if (body == null || body.seedAuthorId() == null || body.seedAuthorId().isBlank()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"seedAuthorId is required and must be non-blank\"}");
        }
        return proxy.forwardPost(
                "/v1/find-lookalikes",
                "/api/marketing/find-lookalikes",
                body,
                false
        );
    }

    @Operation(summary = "Diagnostic: compare legacy vs. current lookalike ranking for a seed author (not cached)")
    @GetMapping(value = "/find-lookalikes/diff", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> findLookalikesDiff(
            @RequestParam("seedAuthorId") String seedAuthorId,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("seedAuthorId", seedAuthorId);
        if (limit != null) q.put("limit", limit);
        return proxy.forwardGet(
                "/v1/find-lookalikes/diff",
                "/api/marketing/find-lookalikes/diff",
                q,
                false,
                null
        );
    }

    @Operation(summary = "Get a user profile (cacheable)")
    @GetMapping(value = "/users/{globalUserId}/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> userProfile(@PathVariable("globalUserId") String globalUserId) {
        return proxy.forwardGet(
                "/v1/users/{globalUserId}/profile",
                "/api/marketing/user-profile/" + encodeSegment(globalUserId),
                null,
                true,
                null
        );
    }

    @Operation(summary = "Get a user report (NOT cacheable: upstream persists a row)")
    @GetMapping(value = "/users/{author}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> userReport(@PathVariable("author") String author) {
        return proxy.forwardGet(
                "/v1/users/{author}/report",
                "/api/marketing/user-report/" + encodeSegment(author),
                null,
                false,
                null
        );
    }

    @Operation(summary = "List users with optional filters (cacheable)")
    @GetMapping(value = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> users(
            @Parameter @RequestParam(value = "audienceClassification", required = false) String audienceClassification,
            @Parameter @RequestParam(value = "influenceTier", required = false) String influenceTier,
            @Parameter @RequestParam(value = "postingStyle", required = false) String postingStyle,
            @Parameter @RequestParam(value = "dominantTone", required = false) String dominantTone,
            @Parameter @RequestParam(value = "primaryPlatform", required = false) String primaryPlatform
    ) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (audienceClassification != null) q.put("audienceClassification", audienceClassification);
        if (influenceTier != null) q.put("influenceTier", influenceTier);
        if (postingStyle != null) q.put("postingStyle", postingStyle);
        if (dominantTone != null) q.put("dominantTone", dominantTone);
        if (primaryPlatform != null) q.put("primaryPlatform", primaryPlatform);
        return proxy.forwardGet(
                "/v1/users",
                "/api/marketing/users",
                q,
                true,
                null
        );
    }

    @Operation(summary = "List user categories (cacheable, longer TTL)")
    @GetMapping(value = "/users/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> userCategories() {
        return proxy.forwardGet(
                "/v1/users/categories",
                "/api/marketing/users/categories",
                null,
                true,
                (long) props.getCache().getCategoriesTtlSeconds()
        );
    }

    @Operation(summary = "Trigger full user sync (NOT cacheable, long-running)")
    @PostMapping(value = "/users/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> userSync() {
        return proxy.forwardPost(
                "/v1/users/sync",
                "/api/marketing/users/sync",
                null,
                true
        );
    }

    @Operation(summary = "Get potential viewers for a genre (cacheable)")
    @GetMapping(value = "/genres/{genre}/potential-viewers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> potentialViewers(@PathVariable("genre") String genre) {
        return proxy.forwardGet(
                "/v1/genres/{genre}/potential-viewers",
                "/api/marketing/genre/" + encodeSegment(genre) + "/potential-viewers",
                null,
                true,
                null
        );
    }

    @Operation(summary = "Get super spreaders for a genre (cacheable)")
    @GetMapping(value = "/genres/{genre}/super-spreaders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> superSpreaders(@PathVariable("genre") String genre) {
        return proxy.forwardGet(
                "/v1/genres/{genre}/super-spreaders",
                "/api/marketing/genre/" + encodeSegment(genre) + "/super-spreaders",
                null,
                true,
                null
        );
    }

    @Operation(summary = "Get channel strategy for a genre (cacheable)")
    @GetMapping(value = "/genres/{genre}/channel-strategy", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> channelStrategy(@PathVariable("genre") String genre) {
        return proxy.forwardGet(
                "/v1/genres/{genre}/channel-strategy",
                "/api/marketing/genre/" + encodeSegment(genre) + "/channel-strategy",
                null,
                true,
                null
        );
    }

    @Operation(summary = "List targets (cacheable)")
    @GetMapping(value = "/targets", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> targets(
            @RequestParam(value = "genre", required = false) String genre,
            @RequestParam(value = "minInfluenceScore", required = false, defaultValue = "0.0") Double minInfluenceScore,
            @RequestParam(value = "platform", required = false) String platform
    ) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (genre != null) q.put("genre", genre);
        q.put("minInfluenceScore", minInfluenceScore);
        if (platform != null) q.put("platform", platform);
        return proxy.forwardGet(
                "/v1/targets",
                "/v1/targets",
                q,
                true,
                null
        );
    }

    @Operation(summary = "Diagnostic: raw author mapping (not cached)")
    @GetMapping(value = "/diagnostics/raw-mapping/{author}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rawMapping(@PathVariable("author") String author) {
        return proxy.forwardGet(
                "/v1/diagnostics/raw-mapping/{author}",
                "/api/test/raw-mapping/" + encodeSegment(author),
                null,
                false,
                null
        );
    }

    @Operation(summary = "Diagnostic: temporal audit (not cached)")
    @GetMapping(value = "/diagnostics/temporal-audit/{author}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> temporalAudit(@PathVariable("author") String author) {
        return proxy.forwardGet(
                "/v1/diagnostics/temporal-audit/{author}",
                "/api/test/temporal-audit/" + encodeSegment(author),
                null,
                false,
                null
        );
    }

    @Operation(summary = "Diagnostic: process user (not cached). Upstream path is /test/..., not /api/test/...")
    @GetMapping(value = "/diagnostics/process-user/{author}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> processUser(@PathVariable("author") String author) {
        return proxy.forwardGet(
                "/v1/diagnostics/process-user/{author}",
                "/test/process-user/" + encodeSegment(author),
                null,
                false,
                null
        );
    }

    private static String encodeSegment(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
