package com.aura.service.proxy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Tag(name = "Health", description = "Liveness/readiness probe")
public class HealthController {

    private final AuraMathProxyService proxy;

    public HealthController(AuraMathProxyService proxy) {
        this.proxy = proxy;
    }

    @Operation(summary = "Liveness/readiness — pings upstream /v1/targets?minInfluenceScore=999999")
    @GetMapping(value = "/healthz", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> healthz() {
        boolean ok = proxy.upstreamReachable();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "UP" : "DOWN");
        body.put("upstream", ok ? "reachable" : "unreachable");
        return ResponseEntity.status(ok ? 200 : 503).body(body);
    }
}
