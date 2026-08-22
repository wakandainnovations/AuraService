package com.aura.service.controller;

import com.aura.service.dto.MomentumCausalReportResponse;
import com.aura.service.service.MomentumCausalReportPdfService;
import com.aura.service.service.MomentumCausalReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post-release "Momentum & Causal Chain" report for a managed entity — audience-behavior pattern
 * analysis over an entity's tracked history (VMI trend, causal-precedence chains, causal-lift-scored
 * users, and F9's non-obvious-lever/playbook-sequence findings). Distinct from the pre-release
 * "Launch Plan" report ({@link EntityMarketingReportController}'s {@code marketing-report}).
 *
 * <p>Ownership is enforced the same way as every other entity-scoped endpoint (see
 * {@link EntityCausalIntelController}): {@link com.aura.service.service.EntityAccessService
 * #assertOwnedByCurrentUser} runs first, so a missing or not-owned entity 404s before any upstream
 * call is made.
 */
@RestController
@RequestMapping("/api/entities")
@RequiredArgsConstructor
@Tag(name = "Momentum & Causal Chain Report",
        description = "Post-release audience-behavior pattern analysis for a managed entity")
public class MomentumCausalReportController {

    private final MomentumCausalReportService reportService;
    private final MomentumCausalReportPdfService pdfService;

    @Operation(summary = "Generate the Momentum & Causal Chain report for an entity")
    @GetMapping(value = "/{id}/momentum-report", produces = MediaType.APPLICATION_JSON_VALUE)
    public MomentumCausalReportResponse getMomentumReport(@PathVariable Long id) {
        return reportService.buildReport(id);
    }

    @Operation(summary = "Generate the Momentum & Causal Chain report as a downloadable PDF")
    @GetMapping("/{id}/momentum-report/pdf")
    public ResponseEntity<byte[]> getMomentumReportPdf(@PathVariable Long id) {
        MomentumCausalReportResponse report = reportService.buildReport(id);
        byte[] pdf = pdfService.render(report);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(pdfService.fileName(report)).build());
        headers.setContentLength(pdf.length);
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }
}
