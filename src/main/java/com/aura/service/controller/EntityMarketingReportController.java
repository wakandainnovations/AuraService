package com.aura.service.controller;

import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.enums.LicenseTier;
import com.aura.service.enums.TimePeriod;
import com.aura.service.licensing.RequiresTier;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prospect-facing marketing intelligence report for a managed entity. Aggregates this service's own
 * analytics (headline reach, sentiment over time, competitive positioning, platform reach, defining
 * moments) together with the upstream AuraMath entity-report, and returns it as a single payload
 * suitable for showing at a high level to a production house's potential customers.
 */
@Validated
@RestController
@RequestMapping("/api/entities/{entityType}/{id}")
@RequiredArgsConstructor
@RequiresTier(value = LicenseTier.DIAMOND, feature = "Intelligence Report")
@Tag(name = "Entity Marketing Report",
        description = "Complete, prospect-facing marketing intelligence report for a managed entity")
public class EntityMarketingReportController {

    private final EntityMarketingReportService reportService;
    private final EntityMarketingReportPdfService pdfService;

    @Operation(summary = "Generate a complete marketing intelligence report for an entity")
    @GetMapping("/marketing-report")
    public ResponseEntity<EntityMarketingReportResponse> getMarketingReport(
            @PathVariable String entityType,
            @PathVariable Long id,
            @Parameter(description = "Window for the sentiment trend / momentum sections")
            @RequestParam(defaultValue = "DAY30") TimePeriod period,
            @Parameter(description = "Days before/after each checkpoint for the defining-moments impact")
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int windowDays
    ) {
        EntityMarketingReportResponse report = reportService.generateReport(
                entityType.toUpperCase(), id, period, windowDays);
        return ResponseEntity.ok(report);
    }

    @Operation(summary = "Generate the complete marketing intelligence report as a downloadable PDF")
    @GetMapping(value = "/marketing-report/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getMarketingReportPdf(
            @PathVariable String entityType,
            @PathVariable Long id,
            @Parameter(description = "Window for the sentiment trend / momentum sections")
            @RequestParam(defaultValue = "DAY30") TimePeriod period,
            @Parameter(description = "Days before/after each checkpoint for the defining-moments impact")
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int windowDays
    ) {
        EntityMarketingReportResponse report = reportService.generateReport(
                entityType.toUpperCase(), id, period, windowDays);
        byte[] pdf = pdfService.render(report);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(pdfService.fileName(report)).build());
        headers.setContentLength(pdf.length);
        return new ResponseEntity<>(pdf, headers, org.springframework.http.HttpStatus.OK);
    }
}
