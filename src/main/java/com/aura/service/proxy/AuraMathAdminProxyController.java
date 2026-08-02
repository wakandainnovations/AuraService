package com.aura.service.proxy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin proxy over the upstream AuraMath {@code /api/admin/**} recompute triggers. All are
 * synchronous and long-running upstream, so requests are routed through the sync client
 * (long read timeout) and never cached. Bodies are ignored on both sides.
 */
@RestController
@RequestMapping("/v1/admin")
@Tag(name = "AuraMath Admin Proxy",
        description = "Thin proxy over the upstream AuraMath /api/admin/* long-running recompute triggers")
public class AuraMathAdminProxyController {

    private final AuraMathProxyService proxy;

    public AuraMathAdminProxyController(AuraMathProxyService proxy) {
        this.proxy = proxy;
    }

    @Operation(summary = "Recompute marketing_target_profiles (Hawkes, MOI, tribes, genres) — long-running")
    @PostMapping(value = "/run-enrichment",
            produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE })
    public ResponseEntity<String> runEnrichment() {
        return proxy.forwardPost("/v1/admin/run-enrichment", "/api/admin/run-enrichment", null, true);
    }

    @Operation(summary = "Recompute corpus-relative engagement_score_raw/engagement_rating — long-running")
    @PostMapping(value = "/run-engagement-rating", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> runEngagementRating() {
        return proxy.forwardPost("/v1/admin/run-engagement-rating", "/api/admin/run-engagement-rating", null, true);
    }

    @Operation(summary = "Rebuild graph_nodes/graph_edges (MOVIE/USER, POSTED_ABOUT/RETWEETED) — long-running")
    @PostMapping(value = "/run-graph-population", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> runGraphPopulation() {
        return proxy.forwardPost("/v1/admin/run-graph-population", "/api/admin/run-graph-population", null, true);
    }

    @Operation(summary = "Repopulate user_identity_link from every distinct author across source tables — long-running")
    @PostMapping(value = "/resolve-identities",
            produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE })
    public ResponseEntity<String> resolveIdentities() {
        return proxy.forwardPost("/v1/admin/resolve-identities", "/api/admin/resolve-identities", null, true);
    }

    @Operation(summary = "Rebuild the synopsis embedding corpus and persist narrative_novelty_score_v2/_raw_v2 — long-running")
    @PostMapping(value = "/recompute-narrative-novelty", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> recomputeNarrativeNovelty() {
        return proxy.forwardPost("/v1/admin/recompute-narrative-novelty", "/api/admin/recompute-narrative-novelty", null, true);
    }

    @Operation(summary = "Same as recompute-narrative-novelty but persists into the legacy narrative_novelty_score column — long-running")
    @PostMapping(value = "/recompute-narrative-novelty-v1", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> recomputeNarrativeNoveltyV1() {
        return proxy.forwardPost("/v1/admin/recompute-narrative-novelty-v1", "/api/admin/recompute-narrative-novelty-v1", null, true);
    }

    @Operation(summary = "Recompute corpus-relative conflict_balance_score from per-sentence sentiment balance — long-running")
    @PostMapping(value = "/recompute-conflict-balance", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> recomputeConflictBalance() {
        return proxy.forwardPost("/v1/admin/recompute-conflict-balance", "/api/admin/recompute-conflict-balance", null, true);
    }
}
