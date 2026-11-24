package com.aura.service.controller;

import com.aura.service.dto.EntitledResponse;
import com.aura.service.dto.RightsValuationReportResponse;
import com.aura.service.licensing.Feature;
import com.aura.service.predictor.BudgetCurrency;
import com.aura.service.service.EntitlementService;
import com.aura.service.service.RightsValuationReportPdfService;
import com.aura.service.service.RightsValuationReportService;
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
 * Rights-valuation report for a tracked movie: how it performed against comparable films, for a
 * production house negotiating OTT / satellite rights. Owner-scoped (404 on a movie the caller doesn't
 * own) and gated like the other prospect-facing report, {@link Feature#INTELLIGENCE_REPORT}: unentitled
 * callers get the masked {@link EntitledResponse} preview, and the PDF endpoint renders no PDF for them.
 */
@Validated
@RestController
@RequestMapping("/api/movies/{id}/rights-valuation-report")
@RequiredArgsConstructor
@Tag(name = "Rights Valuation Report",
        description = "Movie performance against comparable films, for OTT / satellite rights negotiations")
public class RightsValuationReportController {

    private final RightsValuationReportService reportService;
    private final RightsValuationReportPdfService pdfService;
    private final EntitlementService entitlementService;

    @Operation(summary = "Rights-valuation report: the movie against similar-budget, similarly rated, "
            + "same-genre and same-star films, plus its live social reach")
    @GetMapping
    public EntitledResponse<RightsValuationReportResponse> getReport(
            @PathVariable Long id,
            @Parameter(description = "INR (default) or USD")
            @RequestParam(defaultValue = "INR") String currency,
            @Parameter(description = "How many years of releases the language cohorts draw on")
            @RequestParam(defaultValue = "8") @Min(1) @Max(25) int lookbackYears
    ) {
        BudgetCurrency parsed = BudgetCurrency.fromJson(currency);
        return entitlementService.evaluate(Feature.INTELLIGENCE_REPORT,
                () -> reportService.buildReport(id, parsed, lookbackYears));
    }

    @Operation(summary = "The rights-valuation report as a PDF to hand to a buyer")
    @GetMapping("/pdf")
    public ResponseEntity<?> getReportPdf(
            @PathVariable Long id,
            @Parameter(description = "INR (default) or USD")
            @RequestParam(defaultValue = "INR") String currency,
            @Parameter(description = "How many years of releases the language cohorts draw on")
            @RequestParam(defaultValue = "8") @Min(1) @Max(25) int lookbackYears
    ) {
        RightsValuationReportResponse report =
                reportService.buildReport(id, BudgetCurrency.fromJson(currency), lookbackYears);
        if (!entitlementService.isEntitled(Feature.INTELLIGENCE_REPORT.getRequiredTier())) {
            return ResponseEntity.ok(entitlementService.wrap(Feature.INTELLIGENCE_REPORT, report));
        }
        byte[] pdf = pdfService.render(report);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(pdfService.fileName(report)).build());
        headers.setContentLength(pdf.length);
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }
}
