package com.aura.service.proxy;

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

/**
 * Thin proxy over the upstream AuraMath {@code /api/analytics/celebrity} routes, kept
 * parallel to upstream as {@code /v1/analytics/celebrity} and {@code /v1/analytics/celebrity/{entityId}}.
 * Each response is forwarded verbatim on 2xx and TTL-cached; the upstream's real 404 (unknown id, or
 * an entity that is not a CELEBRITY) is relayed through unchanged, and upstream 5xx is mapped to a
 * sanitized HTTP 502 so SQL/stack fragments are never leaked.
 */
@RestController
@RequestMapping("/v1/analytics")
@Tag(name = "AuraMath Analytics Proxy",
        description = "Thin proxy over the upstream AuraMath /api/analytics/celebrity routes")
public class AuraMathAnalyticsProxyController {

    private final AuraMathProxyService proxy;
    private final AuraMathProperties props;

    public AuraMathAnalyticsProxyController(AuraMathProxyService proxy,
                                            AuraMathProperties props) {
        this.proxy = proxy;
        this.props = props;
    }

    @Operation(summary = "List managed entities of type CELEBRITY (cacheable, 5 min)")
    @GetMapping(value = "/celebrity", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> listCelebrities() {
        return proxy.forwardMarketingGet(
                "/v1/analytics/celebrity",
                "/api/analytics/celebrity",
                listTtlSeconds()
        );
    }

    @Operation(summary = "Full analytics for a CELEBRITY by id (404 if unknown or not a CELEBRITY)")
    @GetMapping(value = "/celebrity/{entityId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> celebrityAnalytics(@PathVariable("entityId") String entityId) {
        requireEntityId(entityId);
        return proxy.forwardMarketingGet(
                "/v1/analytics/celebrity/{entityId}",
                "/api/analytics/celebrity/" + encodeSegment(entityId),
                defaultTtlSeconds()
        );
    }

    private long defaultTtlSeconds() {
        return props.getCache().getDefaultTtlSeconds();
    }

    private long listTtlSeconds() {
        return props.getCache().getListTtlSeconds();
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
