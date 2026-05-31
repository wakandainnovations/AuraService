package com.aura.service.controller;

import com.aura.service.dto.ReportAbuseRequest;
import com.aura.service.entity.AbuseReport;
import com.aura.service.service.AbuseReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mentions/{mentionId}/report-abuse")
@RequiredArgsConstructor
public class MentionAbuseReportController {

    private final AbuseReportService abuseReportService;

    @PostMapping
    public ResponseEntity<AbuseReport> reportAbuse(
            @PathVariable("mentionId") Long mentionId,
            @Valid @RequestBody ReportAbuseRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        return abuseReportService.report(mentionId, request, principal.getUsername())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
