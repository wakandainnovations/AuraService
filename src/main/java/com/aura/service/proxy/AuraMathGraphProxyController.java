package com.aura.service.proxy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Thin proxy over the upstream AuraMath {@code /api/graph/users} route — a filterable read API
 * over AuraMath's own precomputed graph tables (populated by its {@code GraphPopulationService}).
 * This is distinct from AuraService's native {@code /api/graph/movies/{movieId}} subgraph endpoint,
 * which reads AuraService's own {@code graph_nodes}/{@code graph_edges} tables.
 */
@RestController
@RequestMapping("/v1/graph")
@Tag(name = "AuraMath Graph Proxy",
        description = "Thin proxy over the upstream AuraMath /api/graph/users route")
public class AuraMathGraphProxyController {

    private final AuraMathProxyService proxy;
    private final AuraMathProperties props;

    public AuraMathGraphProxyController(AuraMathProxyService proxy, AuraMathProperties props) {
        this.proxy = proxy;
        this.props = props;
    }

    @Operation(summary = "Movie/user subgraph for a language (and optional movie filter), cacheable")
    @GetMapping(value = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> graphUsers(
            @RequestParam("language") String language,
            @RequestParam(value = "movie", required = false) String movie
    ) {
        StringBuilder upstreamPath = new StringBuilder("/api/graph/users?language=")
                .append(encodeQueryValue(language));
        if (movie != null && !movie.isBlank()) {
            upstreamPath.append("&movie=").append(encodeQueryValue(movie));
        }
        return proxy.forwardMarketingGet(
                "/v1/graph/users",
                upstreamPath.toString(),
                props.getCache().getDefaultTtlSeconds()
        );
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
