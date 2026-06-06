package com.aura.service.controller;

import com.aura.service.dto.AbuseReportDto;
import com.aura.service.entity.AbuseReport;
import com.aura.service.service.AbuseReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/abuse-reports")
@RequiredArgsConstructor
public class AbuseReportController {

    private final AbuseReportService abuseReportService;

    /**
     * User-wide view of the caller's abuse reports, newest first. Optional {@code status} narrows the
     * list to a single lifecycle stage (e.g. {@code SUBMITTED}, {@code UPHELD}, {@code REJECTED}).
     */
    @GetMapping
    public ResponseEntity<List<AbuseReportDto>> list(
            @RequestParam(value = "status", required = false) AbuseReport.Status status,
            @AuthenticationPrincipal UserDetails principal
    ) {
        return ResponseEntity.ok(abuseReportService.listForUser(principal.getUsername(), status));
    }
}
