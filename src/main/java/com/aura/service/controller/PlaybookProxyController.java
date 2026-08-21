package com.aura.service.controller;

import com.aura.service.proxy.AuraMathProperties;
import com.aura.service.proxy.AuraMathProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin proxy over the upstream AuraMath F7 ({@code playbook_patterns}) route. Not entity-scoped
 * (the cohort is resolved directly from the {@code industry}/{@code language} query params, not
 * from any single entity), so there is no ownership check to enforce here.
 */
@RestController
@RequestMapping("/api/marketing")
@Tag(name = "Playbook Proxy", description = "Thin proxy over the upstream AuraMath /api/marketing/playbook route")
public class PlaybookProxyController {

    private final AuraMathProxyService proxy;
    private final AuraMathProperties props;

    public PlaybookProxyController(AuraMathProxyService proxy, AuraMathProperties props) {
        this.proxy = proxy;
        this.props = props;
    }

    @Operation(summary = "Playbook patterns for an (industry, language) cohort (F7, cacheable)")
    @GetMapping(value = "/playbook", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> playbook(
            @RequestParam("industry") String industry,
            @RequestParam("language") String language
    ) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("industry", industry);
        q.put("language", language);
        return proxy.forwardGet(
                "/api/marketing/playbook",
                "/api/marketing/playbook",
                q,
                true,
                (long) props.getCache().getDefaultTtlSeconds()
        );
    }
}
