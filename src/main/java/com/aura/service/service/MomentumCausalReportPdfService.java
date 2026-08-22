package com.aura.service.service;

import com.aura.service.dto.MomentumCausalReportResponse;
import com.aura.service.dto.MomentumCausalReportResponse.CausalLiftUser;
import com.aura.service.dto.MomentumCausalReportResponse.StatisticalCandidateSection;
import com.aura.service.dto.MomentumCausalReportResponse.TopCausalLiftUsersSection;
import com.aura.service.dto.RecommendedActionCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link MomentumCausalReportResponse} into a PDF using OpenPDF, the same rendering
 * approach as {@link EntityMarketingReportPdfService}: a branded header followed by one section per
 * report field, each degrading to a muted placeholder paragraph (rather than being silently omitted)
 * when that section's status is {@code "insufficient_history"} — so a freshly-tracked entity's PDF
 * still reads as a complete report, not a truncated one.
 */
@Service
public class MomentumCausalReportPdfService {

    private static final Color BRAND = new Color(0x1F, 0x2D, 0x5A);
    private static final Color ACCENT = new Color(0x2E, 0x86, 0xDE);
    private static final Color HEADER_BG = new Color(0x1F, 0x2D, 0x5A);
    private static final Color ROW_ALT = new Color(0xF2, 0xF5, 0xFA);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color HIGH_CONF = new Color(0x16, 0xA3, 0x6E);
    private static final Color LOW_CONF = new Color(0x94, 0x9C, 0xB0);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Color.WHITE);
    private static final Font SUBTITLE = FontFactory.getFont(FontFactory.HELVETICA, 11, new Color(0xD5, 0xDD, 0xEE));
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, BRAND);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
    private static final Font BODY_MUTED = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, MUTED);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
    private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC);

    public byte[] render(MomentumCausalReportResponse report) {
        Document document = new Document(PageSize.A4, 40, 40, 44, 44);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            addHeader(document, report);
            addVmiTrend(document, report.getVmiTrend());
            addCausalChains(document, report.getCausalChains());
            addTopCausalLiftUsers(document, report.getTopCausalLiftUsers());
            addStatisticalSection(document, "Non-Obvious Levers", report.getNonObviousLevers());
            addStatisticalSection(document, "Playbook Matches", report.getPlaybookMatches());

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            if (document.isOpen()) {
                document.close();
            }
            throw new IllegalStateException("Failed to render momentum & causal chain report PDF", e);
        }
    }

    /** A filesystem/header-safe download filename for the rendered report. */
    public String fileName(MomentumCausalReportResponse report) {
        String name = report.getEntityName() != null ? report.getEntityName() : "entity";
        String slug = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = "entity";
        }
        return "momentum-causal-report-" + slug + ".pdf";
    }

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    private void addHeader(Document document, MomentumCausalReportResponse report) throws DocumentException {
        String name = report.getEntityName() != null ? report.getEntityName() : "Entity";

        PdfPTable banner = fullWidthTable(1);
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(BRAND);
        cell.setBorder(0);
        cell.setPadding(16f);
        cell.addElement(new Paragraph("MOMENTUM & CAUSAL CHAIN REPORT", SUBTITLE));
        cell.addElement(new Paragraph(name, TITLE));
        banner.addCell(cell);
        document.add(banner);

        String stamp = report.getGeneratedAt() != null ? STAMP.format(report.getGeneratedAt())
                : STAMP.format(Instant.EPOCH);
        Paragraph sub = new Paragraph("Generated " + stamp, BODY_MUTED);
        sub.setSpacingBefore(6f);
        sub.setSpacingAfter(4f);
        document.add(sub);
    }

    private void addVmiTrend(Document document, JsonNode vmiTrend) throws DocumentException {
        sectionHeader(document, "Viewership Momentum Index Trend");
        if (!isOk(vmiTrend)) {
            insufficientHistory(document, vmiTrend);
            return;
        }
        JsonNode peakDay = vmiTrend.get("peakDay");
        if (peakDay != null && peakDay.isObject()) {
            Paragraph p = new Paragraph(String.format(Locale.US,
                    "Peak day: %s (day index %s, engagement volume %s).",
                    orDash(text(peakDay, "calendarDate")), orDash(text(peakDay, "dayIndex")),
                    orDash(text(peakDay, "dailyEngagementVolume"))), BODY_BOLD);
            p.setSpacingAfter(6f);
            document.add(p);
        }
        JsonNode series = vmiTrend.get("series");
        if (series == null || !series.isArray() || series.isEmpty()) {
            document.add(new Paragraph("No daily VMI rows in this series.", BODY_MUTED));
            return;
        }
        PdfPTable table = fullWidthTable(new float[]{1, 2, 1.4f, 1.4f});
        headerRow(table, "Day", "Engagement Volume", "Cohort Z-score", "Cumulative Volume");
        int i = 0;
        for (JsonNode row : series) {
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, orDash(text(row, "day_index")), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, orDash(text(row, "daily_engagement_volume")), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, orDash(text(row, "cohort_zscore")), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, orDash(text(row, "cumulative_engagement_volume")), bg, Element.ALIGN_RIGHT, false);
        }
        document.add(table);
    }

    private void addCausalChains(Document document, JsonNode causalChains) throws DocumentException {
        sectionHeader(document, "Causal Precedence Chains");
        if (!isOk(causalChains)) {
            insufficientHistory(document, causalChains);
            return;
        }
        JsonNode chains = causalChains.get("chains");
        if (chains == null || !chains.isArray() || chains.isEmpty()) {
            document.add(new Paragraph("No causal-precedence chains for this cohort.", BODY_MUTED));
            return;
        }
        int chainIndex = 1;
        for (JsonNode chain : chains) {
            Paragraph title = new Paragraph(String.format(Locale.US, "Chain %d — path score %s",
                    chainIndex++, orDash(text(chain, "pathScore"))), BODY_BOLD);
            title.setSpacingBefore(8f);
            title.setSpacingAfter(4f);
            document.add(title);

            JsonNode edges = chain.get("edges");
            if (edges == null || !edges.isArray() || edges.isEmpty()) {
                continue;
            }
            PdfPTable table = fullWidthTable(new float[]{1.6f, 1.6f, 0.8f, 1.2f, 1.4f, 1.6f});
            headerRow(table, "From", "To", "Lag", "FDR q", "Effect (r²)", "Entities Supporting");
            int i = 0;
            for (JsonNode edge : edges) {
                Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
                bodyCell(table, orDash(text(edge, "from_series")), bg, Element.ALIGN_LEFT, false);
                bodyCell(table, orDash(text(edge, "to_series")), bg, Element.ALIGN_LEFT, false);
                bodyCell(table, orDash(text(edge, "lag")), bg, Element.ALIGN_RIGHT, false);
                bodyCell(table, orDash(text(edge, "fdr_q_value")), bg, Element.ALIGN_RIGHT, false);
                bodyCell(table, orDash(text(edge, "effect_size_r2")), bg, Element.ALIGN_RIGHT, false);
                bodyCell(table, orDash(text(edge, "n_entities_supporting")), bg, Element.ALIGN_RIGHT, false);
            }
            document.add(table);
        }
    }

    private void addTopCausalLiftUsers(Document document, TopCausalLiftUsersSection section) throws DocumentException {
        sectionHeader(document, "Top Causal-Lift Users");
        if (section == null || !"ok".equals(section.getStatus())) {
            insufficientHistory(document, section != null ? section.getDetails() : null);
            return;
        }
        List<CausalLiftUser> users = section.getUsers();
        if (users == null || users.isEmpty()) {
            document.add(new Paragraph("No qualifying causal-lift users.", BODY_MUTED));
            return;
        }
        PdfPTable table = fullWidthTable(new float[]{2.2f, 1.2f, 1, 1.2f, 1.2f});
        headerRow(table, "User", "Causal Lift Score", "Confidence", "Qualifying Events", "Mentions");
        int i = 0;
        for (CausalLiftUser u : users) {
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, orDash(u.getGlobalUserId()), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, u.getCausalLiftScore() != null
                    ? String.format(Locale.US, "%.3f", u.getCausalLiftScore()) : "—", bg, Element.ALIGN_RIGHT, false);
            confidenceCell(table, u.getConfidence(), bg);
            bodyCell(table, u.getNQualifyingEvents() != null ? String.valueOf(u.getNQualifyingEvents()) : "—",
                    bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, u.getMentionCount() != null ? String.valueOf(u.getMentionCount()) : "—",
                    bg, Element.ALIGN_RIGHT, false);
        }
        document.add(table);
    }

    private void addStatisticalSection(Document document, String title, StatisticalCandidateSection section)
            throws DocumentException {
        sectionHeader(document, title);
        if (section == null || !"ok".equals(section.getStatus())) {
            insufficientHistory(document, section != null ? section.getDetails() : null);
            return;
        }
        List<RecommendedActionCandidate> candidates = section.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            document.add(new Paragraph("No candidates.", BODY_MUTED));
            return;
        }
        PdfPTable table = fullWidthTable(new float[]{2.4f, 1, 1, 1.2f, 3});
        headerRow(table, "Finding", "Confidence", "FDR q", "Sample (n)", "Detail");
        int i = 0;
        for (RecommendedActionCandidate c : candidates) {
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            RecommendedActionCandidate.StatisticalEvidence ev = c.statisticalEvidence();
            bodyCell(table, orDash(c.factorName()), bg, Element.ALIGN_LEFT, true);
            bodyCell(table, c.confidencePct() + "%", bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, ev != null && ev.fdrQValue() != null
                    ? String.format(Locale.US, "%.4f", ev.fdrQValue()) : "—", bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, ev != null && ev.nEntities() != null ? String.valueOf(ev.nEntities()) : "—",
                    bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, statisticalDetail(ev), bg, Element.ALIGN_LEFT, false);
        }
        document.add(table);
    }

    private static String statisticalDetail(RecommendedActionCandidate.StatisticalEvidence ev) {
        if (ev == null) {
            return "—";
        }
        if (ev.featureName() != null) {
            return ev.featureName() + (ev.direction() != null ? " (" + ev.direction() + ")" : "");
        }
        if (ev.patternSequence() != null && !ev.patternSequence().isEmpty()) {
            return String.join(" → ", ev.patternSequence());
        }
        return "—";
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    private void sectionHeader(Document document, String title) throws DocumentException {
        Paragraph p = new Paragraph(title, SECTION);
        p.setSpacingBefore(14f);
        p.setSpacingAfter(6f);
        document.add(p);
    }

    private void insufficientHistory(Document document, JsonNode node) throws DocumentException {
        String details = node != null && node.has("details") ? text(node, "details") : null;
        insufficientHistory(document, details);
    }

    private void insufficientHistory(Document document, String details) throws DocumentException {
        String message = "Insufficient tracked history yet"
                + (details != null && !details.isBlank() ? " — " + details : ".");
        document.add(new Paragraph(message, BODY_MUTED));
    }

    private boolean isOk(JsonNode node) {
        return node != null && node.isObject() && "ok".equals(text(node, "status"));
    }

    private void confidenceCell(PdfPTable table, String confidence, Color bg) {
        Color color = "HIGH".equalsIgnoreCase(confidence) ? HIGH_CONF : LOW_CONF;
        PdfPCell cell = new PdfPCell(new Phrase(orDash(confidence),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, color)));
        cell.setBackgroundColor(bg);
        cell.setPadding(5f);
        cell.setBorderColor(new Color(0xE6, 0xE9, 0xF0));
        table.addCell(cell);
    }

    private void headerRow(PdfPTable table, String... headers) {
        for (int i = 0; i < headers.length; i++) {
            PdfPCell cell = new PdfPCell(new Phrase(headers[i], TH));
            cell.setBackgroundColor(HEADER_BG);
            cell.setPadding(6f);
            cell.setBorder(0);
            cell.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            table.addCell(cell);
        }
    }

    private void bodyCell(PdfPTable table, String textValue, Color bg, int align, boolean bold) {
        PdfPCell cell = new PdfPCell(new Phrase(textValue != null ? textValue : "", bold ? BODY_BOLD : BODY));
        cell.setBackgroundColor(bg);
        cell.setPadding(5f);
        cell.setBorderColor(new Color(0xE6, 0xE9, 0xF0));
        cell.setHorizontalAlignment(align);
        table.addCell(cell);
    }

    private PdfPTable fullWidthTable(int columns) {
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        table.setSpacingBefore(2f);
        return table;
    }

    private PdfPTable fullWidthTable(float[] widths) {
        PdfPTable table = new PdfPTable(widths.length);
        try {
            table.setWidths(widths);
        } catch (DocumentException ignored) {
            // setWidths only throws when the array length mismatches the column count, which it never does here.
        }
        table.setWidthPercentage(100);
        table.setSpacingBefore(2f);
        return table;
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private static String text(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String s = value.asText();
        return s.isBlank() ? null : s;
    }

    private static String orDash(String s) {
        return s != null && !s.isBlank() ? s : "—";
    }
}
