package com.aura.service.controller;

import com.aura.service.dto.AlertRuleRequest;
import com.aura.service.dto.AlertRuleResponse;
import com.aura.service.service.AlertRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/alert-rules")
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    @GetMapping
    public ResponseEntity<List<AlertRuleResponse>> list(@AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(alertRuleService.list(user.getUsername()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlertRuleResponse> get(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UserDetails user
    ) {
        return alertRuleService.get(id, user.getUsername())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AlertRuleResponse> create(
            @Valid @RequestBody AlertRuleRequest request,
            @AuthenticationPrincipal UserDetails user
    ) {
        AlertRuleResponse created = alertRuleService.create(request, user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AlertRuleResponse> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody AlertRuleRequest request,
            @AuthenticationPrincipal UserDetails user
    ) {
        return alertRuleService.update(id, request, user.getUsername())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UserDetails user
    ) {
        return alertRuleService.delete(id, user.getUsername())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
