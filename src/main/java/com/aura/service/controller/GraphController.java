package com.aura.service.controller;

import com.aura.service.dto.GraphSubgraphResponse;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.enums.GraphRelationType;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.GraphQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphController {

    /** Depth is a fan-out multiplier, so the ceiling is much lower than a flat list's page size. */
    private static final int MAX_DEPTH = 5;
    private static final int MAX_PAGE_SIZE = 200;

    private final GraphQueryService graphQueryService;
    private final EntityAccessService entityAccessService;

    @GetMapping("/movies/{movieId}")
    public ResponseEntity<GraphSubgraphResponse> getMovieSubgraph(
            @PathVariable Long movieId,
            @RequestParam(required = false) GraphNodeType nodeType,
            @RequestParam(required = false) GraphRelationType relationType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int depth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) Long ownerId
    ) {
        // Admins may scope to a specific user via ownerId (the movie must belong to them); a
        // non-admin passing ownerId is rejected (403). Otherwise this is the normal ownership check.
        entityAccessService.assertAccessible(movieId, ownerId);

        int boundedDepth = Math.max(1, Math.min(depth, MAX_DEPTH));
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        GraphSubgraphResponse response = graphQueryService.getMovieSubgraph(
                movieId, nodeType, relationType, from, to, boundedDepth, pageable);
        return ResponseEntity.ok(response);
    }
}
