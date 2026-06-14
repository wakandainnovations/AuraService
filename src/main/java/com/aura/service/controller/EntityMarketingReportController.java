package com.aura.service.controller;

import com.aura.service.dto.EntitledResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.enums.TimePeriod;
import com.aura.service.licensing.Feature;
import com.aura.service.service.EntitlementService;
import com.aura.service.service.EntityMarketingReportPdfService;
import com.aura.service.service.EntityMarketingReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prospect-facing marketing intelligence report for a managed entity — the {@link Feature#INTELLIGENCE_REPORT
 * DIAMOND}-tier "Intelligence Report" feature. Under-tier users are no longer rejected with a {@code 403};
 * the JSON endpoint answers {@code 200} with an {@link EntitledResponse} (real report when entitled, a
 * masked, blurred teaser otherwise), and the PDF endpoint streams the PDF when entitled or hands back the
 * same masked JSON envelope when not (no PDF is rendered for an unentitled user).
 */
@Validated
@RestController
@RequestMapping("/api/entities/{entityType}/{id}")
@RequiredArgsConstructor
@Tag(name = "Entity Marketing Report",
        description = "Complete, prospect-facing marketing intelligence report for a managed entity")
public class EntityMarketingReportController {

    private final EntityMarketingReportService reportService;
    private final EntityMarketingReportPdfService pdfService;
    private final EntitlementService entitlementService;

    @Operation(summary = "Generate a complete marketing intelligence report for an entity")
    @GetMapping("/marketing-report")
    public EntitledResponse<EntityMarketingReportResponse> getMarketingReport(
            @PathVariable String entityType,
            @PathVariable Long id,
            @Parameter(description = "Window for the sentiment trend / momentum sections")
            @RequestParam(defaultValue = "DAY30") TimePeriod period,
            @Parameter(description = "Days before/after each checkpoint for the defining-moments impact")
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int windowDays
    ) {
        return entitlementService.evaluate(Feature.INTELLIGENCE_REPORT,
                () -> reportService.generateReport(entityType.toUpperCase(), id, period, windowDays));
    }

    @Operation(summary = "Generate the complete marketing intelligence report as a downloadable PDF")
    @GetMapping("/marketing-report/pdf")
    public ResponseEntity<?> getMarketingReportPdf(
            @PathVariable String entityType,
            @PathVariable Long id,
            @Parameter(description = "Window for the sentiment trend / momentum sections")
            @RequestParam(defaultValue = "DAY30") TimePeriod period,
            @Parameter(description = "Days before/after each checkpoint for the defining-moments impact")
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int windowDays
    ) {
        EntityMarketingReportResponse report = reportService.generateReport(
                entityType.toUpperCase(), id, period, windowDays);

        // Don't render (or stream) a PDF for someone who can't have it — hand back the same masked JSON
        // envelope the JSON endpoint would, so the UI can show a locked teaser uniformly.
        if (!entitlementService.isEntitled(Feature.INTELLIGENCE_REPORT.getRequiredTier())) {
            return ResponseEntity.ok(entitlementService.wrap(Feature.INTELLIGENCE_REPORT, report));
        }

        byte[] pdf = pdfService.render(report);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(pdfService.fileName(report)).build());
        headers.setContentLength(pdf.length);
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }
}
