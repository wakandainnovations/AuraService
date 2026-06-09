package com.aura.service.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin proxy over the upstream AuraMath {@code /api/marketing/**} routes. Paths
 * are kept parallel to upstream: {@code /v1/marketing/genre/{genre}/...},
 * {@code /v1/marketing/party/{party}/...}, {@code /v1/marketing/celebrity/{celebrity}/...}.
 * Each response is forwarded verbatim on 2xx, cached in-memory, and upstream 5xx
 * is mapped to a sanitized HTTP 502 so SQL/stack fragments are never leaked.
 */
@RestController
@RequestMapping("/v1/marketing")
@Tag(name = "AuraMath Marketing Proxy",
        description = "Thin proxy over the upstream AuraMath /api/marketing/* routes")
public class AuraMathMarketingProxyController {

    private final AuraMathProxyService proxy;
    private final AuraMathProperties props;
    private final ObjectMapper objectMapper;

    public AuraMathMarketingProxyController(AuraMathProxyService proxy,
                                            AuraMathProperties props,
                                            ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Genres
    // ------------------------------------------------------------------

    @Operation(summary = "List all movie genres (cacheable, 5 min)")
    @GetMapping(value = "/genre", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> listGenres() {
        return proxy.forwardMarketingGet(
                "/v1/marketing/genre",
                "/api/marketing/genre",
                listTtlSeconds()
        );
    }

    @Operation(summary = "Get potential viewers for a genre (cacheable, 60s)")
    @GetMapping(value = "/genre/{genre}/potential-viewers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> genrePotentialViewers(@PathVariable("genre") String genre) {
        return proxy.forwardMarketingGet(
                "/v1/marketing/genre/{genre}/potential-viewers",
                "/api/marketing/genre/" + encodeSegment(genre) + "/potential-viewers",
                defaultTtlSeconds()
        );
    }

    @Operation(summary = "Get super spreaders for a genre (cacheable, 60s)")
    @GetMapping(value = "/genre/{genre}/super-spreaders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> genreSuperSpreaders(@PathVariable("genre") String genre) {
        return proxy.forwardMarketingGet(
                "/v1/marketing/genre/{genre}/super-spreaders",
                "/api/marketing/genre/" + encodeSegment(genre) + "/super-spreaders",
                defaultTtlSeconds()
        );
    }

    @Operation(summary = "Get channel strategy for a genre (cacheable, 60s)")
    @GetMapping(value = "/genre/{genre}/channel-strategy", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> genreChannelStrategy(@PathVariable("genre") String genre) {
        return proxy.forwardMarketingGet(
                "/v1/marketing/genre/{genre}/channel-strategy",
                "/api/marketing/genre/" + encodeSegment(genre) + "/channel-strategy",
                defaultTtlSeconds()
        );
    }

    // ------------------------------------------------------------------
    // Political parties
    // ------------------------------------------------------------------

    @Operation(summary = "List political parties (cacheable, 5 min)")
    @GetMapping(value = "/party", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> listParties() {
        return proxy.forwardMarketingGet(
                "/v1/marketing/party",
                "/api/marketing/party",
                listTtlSeconds()
        );
    }

    @Operation(summary = "Get potential voters for a party (cacheable, 60s)")
    @GetMapping(value = "/party/{party}/potential-voters", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> partyPotentialVoters(@PathVariable("party") String party) {
        return proxy.forwardMarketingGet(
                "/v1/marketing/party/{party}/potential-voters",
                "/api/marketing/party/" + encodeSegment(party) + "/potential-voters",
                defaultTtlSeconds()
        );
    }

    @Operation(summary = "Get super spreaders for a party (cacheable, 60s)")
    @GetMapping(value = "/party/{party}/super-spreaders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> partySuperSpreaders(@PathVariable("party") String party) {
        return proxy.forwardMarketingGet(
                "/v1/marketing/party/{party}/super-spreaders",
                "/api/marketing/party/" + encodeSegment(party) + "/super-spreaders",
                defaultTtlSeconds()
        );
    }

    @Operation(summary = "Get channel strategy for a party (cacheable, 60s)")
    @GetMapping(value = "/party/{party}/channel-strategy", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> partyChannelStrategy(@PathVariable("party") String party) {
        return proxy.forwardMarketingGet(
                "/v1/marketing/party/{party}/channel-strategy",
                "/api/marketing/party/" + encodeSegment(party) + "/channel-strategy",
                defaultTtlSeconds()
        );
    }

    // ------------------------------------------------------------------
    // Celebrities
    // ------------------------------------------------------------------

    @Operation(summary = "List celebrities (cacheable, 5 min)")
    @GetMapping(value = "/celebrity", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> listCelebrities() {
        return proxy.forwardMarketingGet(
                "/v1/marketing/celebrity",
                "/api/marketing/celebrity",
                listTtlSeconds()
        );
    }

    @Operation(summary = "Get potential fans for a celebrity (cacheable, 60s)")
    @GetMapping(value = "/celebrity/{celebrity}/potential-fans", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> celebrityPotentialFans(@PathVariable("celebrity") String celebrity) {
        return proxy.forwardMarketingGet(
                "/v1/marketing/celebrity/{celebrity}/potential-fans",
                "/api/marketing/celebrity/" + encodeSegment(celebrity) + "/potential-fans",
                defaultTtlSeconds()
        );
    }

    @Operation(summary = "Get super fans for a celebrity (cacheable, 60s)")
    @GetMapping(value = "/celebrity/{celebrity}/super-fans", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> celebritySuperFans(@PathVariable("celebrity") String celebrity) {
        return proxy.forwardMarketingGet(
                "/v1/marketing/celebrity/{celebrity}/super-fans",
                "/api/marketing/celebrity/" + encodeSegment(celebrity) + "/super-fans",
                defaultTtlSeconds()
        );
    }

    @Operation(summary = "Get channel strategy for a celebrity (cacheable, 60s)")
    @GetMapping(value = "/celebrity/{celebrity}/channel-strategy", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> celebrityChannelStrategy(@PathVariable("celebrity") String celebrity) {
        return proxy.forwardMarketingGet(
                "/v1/marketing/celebrity/{celebrity}/channel-strategy",
                "/api/marketing/celebrity/" + encodeSegment(celebrity) + "/channel-strategy",
                defaultTtlSeconds()
        );
    }

    // ------------------------------------------------------------------
    // Entity intelligence reports
    //
    // Two upstream report surfaces with different shapes:
    //  - entity-report/{id} (prospect-facing) → upstream PDF endpoint: binary
    //    application/pdf on 200 (bytes + Content-Disposition relayed verbatim), a real
    //    text/plain 404 forwarded through, upstream 5xx / timeout mapped to a 502 envelope.
    //  - entity/{id}/report (in-app) → JSON report: the upstream "No entity found" 200 is
    //    translated to a 404, the "no scored history" 200 passes through unchanged, and
    //    upstream 5xx / timeout map to a 502 envelope.
    // ------------------------------------------------------------------

    @Operation(summary = "Shareable entity intelligence report PDF (prospect-facing)")
    @GetMapping(value = "/entity-report/{entityId}",
            produces = { MediaType.APPLICATION_PDF_VALUE, MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE })
    public ResponseEntity<byte[]> shareableEntityReport(@PathVariable("entityId") String entityId) {
        requireEntityId(entityId);
        return proxy.forwardEntityReportPdf(
                "/v1/marketing/entity-report/{entityId}",
                "/api/marketing/entity-report/" + encodeSegment(entityId) + "/pdf",
                entityId
        );
    }

    @Operation(summary = "In-app entity intelligence report (logged-in user view)")
    @GetMapping(value = "/entity/{entityId}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> inAppEntityReport(@PathVariable("entityId") String entityId) {
        requireEntityId(entityId);
        return proxy.forwardEntityReport(
                "/v1/marketing/entity/{entityId}/report",
                "/api/marketing/entity/" + encodeSegment(entityId) + "/report",
                entityId
        );
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    @Operation(summary = "List wrapped marketing routes and the upstream they map to")
    @GetMapping(value = "/_catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> catalog() {
        List<Map<String, String>> routes = new ArrayList<>();
        routes.add(route("GET", "/v1/marketing/genre", "/api/marketing/genre"));
        routes.add(route("GET", "/v1/marketing/genre/{genre}/potential-viewers", "/api/marketing/genre/{genre}/potential-viewers"));
        routes.add(route("GET", "/v1/marketing/genre/{genre}/super-spreaders", "/api/marketing/genre/{genre}/super-spreaders"));
        routes.add(route("GET", "/v1/marketing/genre/{genre}/channel-strategy", "/api/marketing/genre/{genre}/channel-strategy"));
        routes.add(route("GET", "/v1/marketing/party", "/api/marketing/party"));
        routes.add(route("GET", "/v1/marketing/party/{party}/potential-voters", "/api/marketing/party/{party}/potential-voters"));
        routes.add(route("GET", "/v1/marketing/party/{party}/super-spreaders", "/api/marketing/party/{party}/super-spreaders"));
        routes.add(route("GET", "/v1/marketing/party/{party}/channel-strategy", "/api/marketing/party/{party}/channel-strategy"));
        routes.add(route("GET", "/v1/marketing/celebrity", "/api/marketing/celebrity"));
        routes.add(route("GET", "/v1/marketing/celebrity/{celebrity}/potential-fans", "/api/marketing/celebrity/{celebrity}/potential-fans"));
        routes.add(route("GET", "/v1/marketing/celebrity/{celebrity}/super-fans", "/api/marketing/celebrity/{celebrity}/super-fans"));
        routes.add(route("GET", "/v1/marketing/celebrity/{celebrity}/channel-strategy", "/api/marketing/celebrity/{celebrity}/channel-strategy"));
        routes.add(route("GET", "/v1/marketing/entity-report/{entityId}", "/api/marketing/entity-report/{entityId}/pdf"));
        routes.add(route("GET", "/v1/marketing/entity/{entityId}/report", "/api/marketing/entity/{entityId}/report"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("upstreamBaseUrl", props.getBaseUrl());
        payload.put("totalRoutes", routes.size());
        payload.put("routes", routes);

        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            return ResponseEntity.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"catalog_serialization_failure\"}");
        }
    }

    private long defaultTtlSeconds() {
        return props.getCache().getDefaultTtlSeconds();
    }

    private long listTtlSeconds() {
        return props.getCache().getListTtlSeconds();
    }

    private static Map<String, String> route(String method, String wrapperPath, String upstreamPath) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("method", method);
        m.put("wrapperPath", wrapperPath);
        m.put("upstreamPath", upstreamPath);
        return m;
    }

    /**
     * The entityId arrives as an opaque string (a {@code managed_entities} id) — it is NOT assumed
     * numeric and is forwarded verbatim. Reject only empty/blank values before hitting upstream.
     */
    private static void requireEntityId(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entityId must not be empty");
        }
    }

    private static String encodeSegment(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
