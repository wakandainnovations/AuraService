package com.aura.service.controller;

import com.aura.service.proxy.AuraMathProperties;
import com.aura.service.proxy.AuraMathProxyService;
import com.aura.service.service.EntityAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entity-scoped proxy over the upstream AuraMath F1 ({@code entity_daily_vmi}), F4
 * ({@code causal_precedence_edges}/{@code causal_precedence_chains}) and F5
 * ({@code nonobvious_lever_findings}) read endpoints. Each response is forwarded verbatim on
 * 2xx and TTL-cached (same {@code forwardGet} + {@link com.aura.service.proxy.TtlCache} pattern
 * as the other AuraMath marketing proxies), but only after ownership of the entity is verified -
 * an entity that is missing <em>or</em> not owned by the caller surfaces as a 404 before any
 * upstream call (cached or not) is made.
 */
@RestController
@RequestMapping("/api/entities")
@Tag(name = "Entity Causal Intelligence Proxy",
        description = "Entity-scoped proxy over the upstream AuraMath F1/F4/F5 causal-intelligence routes")
public class EntityCausalIntelController {

    private final AuraMathProxyService proxy;
    private final AuraMathProperties props;
    private final EntityAccessService entityAccessService;

    public EntityCausalIntelController(AuraMathProxyService proxy,
                                       AuraMathProperties props,
                                       EntityAccessService entityAccessService) {
        this.proxy = proxy;
        this.props = props;
        this.entityAccessService = entityAccessService;
    }

    @Operation(summary = "Viewership Momentum Index day-by-day series for an entity (F1, cacheable)")
    @GetMapping(value = "/{id}/vmi", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> vmi(@PathVariable Long id) {
        entityAccessService.assertOwnedByCurrentUser(id);
        return proxy.forwardGet(
                "/api/entities/{id}/vmi",
                "/api/marketing/entity/" + id + "/vmi",
                null,
                true,
                defaultTtlSeconds()
        );
    }

    @Operation(summary = "Causal-precedence chains for an entity's (industry, language) cohort (F4, cacheable)")
    @GetMapping(value = "/{id}/causal-chains", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> causalChains(@PathVariable Long id) {
        entityAccessService.assertOwnedByCurrentUser(id);
        return proxy.forwardGet(
                "/api/entities/{id}/causal-chains",
                "/api/marketing/entity/" + id + "/causal-chains",
                null,
                true,
                defaultTtlSeconds()
        );
    }

    @Operation(summary = "Pooled ('ALL' cohort) nonobvious-lever findings (F5, cacheable)")
    @GetMapping(value = "/{id}/nonobvious-levers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> nonobviousLevers(@PathVariable Long id) {
        entityAccessService.assertOwnedByCurrentUser(id);
        return proxy.forwardGet(
                "/api/entities/{id}/nonobvious-levers",
                "/api/marketing/entity/" + id + "/nonobvious-levers",
                null,
                true,
                defaultTtlSeconds()
        );
    }

    private long defaultTtlSeconds() {
        return props.getCache().getDefaultTtlSeconds();
    }
}
