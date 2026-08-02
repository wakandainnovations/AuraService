package com.aura.service.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin proxy over the upstream AuraMath Ask engine ({@code /api/ask/**}). Request/response
 * bodies (including any per-request target-DB {@code connection}/{@code password}) are forwarded
 * verbatim to AuraMath over the existing outbound HTTP client — this service never inspects or
 * logs them. Upstream's rich 4xx contracts (clarification, validation, unsafe-SQL) are relayed
 * unchanged; only 5xx is sanitized, consistent with the rest of the marketing-style proxy surface.
 */
@RestController
@RequestMapping("/v1/ask")
@Tag(name = "AuraMath Ask Engine Proxy",
        description = "Thin proxy over the upstream AuraMath /api/ask/* natural-language query engine")
public class AuraMathAskProxyController {

    private final AuraMathProxyService proxy;
    private final AuraMathProperties props;

    public AuraMathAskProxyController(AuraMathProxyService proxy, AuraMathProperties props) {
        this.proxy = proxy;
        this.props = props;
    }

    @Operation(summary = "List the Ask engine's registered target databases (name/driver/host only; cacheable)")
    @GetMapping(value = "/databases", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> databases() {
        return proxy.forwardMarketingGet(
                "/v1/ask/databases",
                "/api/ask/databases",
                props.getCache().getListTtlSeconds()
        );
    }

    @Operation(summary = "Probe a target database connection read-only (not cached; credentials never logged)")
    @PostMapping(value = "/test-connection",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> testConnection(@RequestBody(required = false) JsonNode body) {
        return proxy.forwardMarketingPost(
                "/v1/ask/test-connection",
                "/api/ask/test-connection",
                body,
                false
        );
    }

    @Operation(summary = "Answer a natural-language question against the registry or an explicit target (not cached)")
    @PostMapping(value = "",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> ask(@RequestBody(required = false) JsonNode body) {
        return proxy.forwardMarketingPost(
                "/v1/ask",
                "/api/ask",
                body,
                true
        );
    }

    @Operation(summary = "Ask engine operational counters (counts only; not cached)")
    @GetMapping(value = "/admin/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> metrics() {
        return proxy.forwardMarketingGetUncached("/v1/ask/admin/metrics", "/api/ask/admin/metrics");
    }
}
