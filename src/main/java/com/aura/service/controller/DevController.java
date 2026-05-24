package com.aura.service.controller;

import com.aura.service.repository.UserEntityViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
@Profile("!prod")
public class DevController {

    private final UserEntityViewRepository viewRepository;

    @PostMapping("/reset-demo")
    @Transactional
    public ResponseEntity<Map<String, Object>> resetDemo() {
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        int updated = viewRepository.resetAllLastSeen(thirtyDaysAgo);
        return ResponseEntity.ok(Map.of(
                "reset", true,
                "rows_updated", updated,
                "last_seen_at", thirtyDaysAgo.toString()
        ));
    }
}
