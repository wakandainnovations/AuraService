package com.aura.service.controller;

import com.aura.service.dto.AlertResponse;
import com.aura.service.dto.CreateAlertRequest;
import com.aura.service.dto.DismissAlertRequest;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.service.AlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @PostMapping
    public ResponseEntity<AlertResponse> create(@Valid @RequestBody CreateAlertRequest request) {
        return alertService.create(request)
                .map(r -> ResponseEntity.status(HttpStatus.CREATED).body(r))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @GetMapping
    public ResponseEntity<Page<AlertResponse>> list(
            @RequestParam(value = "entityId", required = false) Long entityId,
            @RequestParam(value = "status", required = false) SentimentAlert.Status status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(alertService.list(entityId, status, page, size));
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<AlertResponse> ack(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UserDetails user
    ) {
        return alertService.ack(id, user.getUsername())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<AlertResponse> dismiss(
            @PathVariable("id") Long id,
            @Valid @RequestBody DismissAlertRequest request,
            @AuthenticationPrincipal UserDetails user
    ) {
        return alertService.dismiss(id, request.getReason(), user.getUsername())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
