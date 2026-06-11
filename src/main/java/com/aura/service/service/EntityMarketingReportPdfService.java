package com.aura.service.service;

import com.aura.service.dto.CompetitorSnapshot;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityMarketingReportResponse.CompetitivePositioning;
import com.aura.service.dto.EntityMarketingReportResponse.HeadlineMetrics;
import com.aura.service.dto.KeywordDto;
import com.aura.service.dto.SentimentOverTimeResponse;
import com.aura.service.dto.TimeSeriesData;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders an {@link EntityMarketingReportResponse} into a polished, prospect-facing PDF using
 * OpenPDF. The layout mirrors the JSON report: a branded header, the deterministic highlights, the
 * headline metrics, competitive positioning, platform reach, defining moments, a compact sentiment
 * trend, and the embedded AuraMath intelligence. Sections that are absent from the report (graceful
 * degradation) are simply skipped.
 */
@Slf4j
@Service
public class EntityMarketingReportPdfService {

    private static final Color BRAND = new Color(0x1F, 0x2D, 0x5A);     // deep navy
    private static final Color ACCENT = new Color(0x2E, 0x86, 0xDE);    // blue
    private static final Color HEADER_BG = new Color(0x1F, 0x2D, 0x5A);
    private static final Color ROW_ALT = new Color(0xF2, 0xF5, 0xFA);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Color.WHITE);
    private static final Font SUBTITLE = FontFactory.getFont(FontFactory.HELVETICA, 11, new Color(0xD5, 0xDD, 0xEE));
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, BRAND);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
    private static final Font BODY_MUTED = FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
    private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
    private static final Font HIGHLIGHT = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC);

    public byte[] render(EntityMarketingReportResponse report) {
        Document document = new Document(PageSize.A4, 40, 40, 44, 44);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            addHeader(document, report);
            addHighlights(document, report.getHighlights());
            addHeadlineMetrics(document, report.getHeadlineMetrics());
            addCompetitivePositioning(document, report.getCompetitivePositioning());
            addPlatformReach(document, report.getPlatformReach());
            addDefiningMoments(document, report.getDefiningMoments());
            addSentimentTrend(document, report.getSentimentTrend());
            addAuraMath(document, report.getAuraMathStatus(), report.getAuraMathIntelligence());

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            // Tear the half-built document down so the writer doesn't leave a dangling open document.
            if (document.isOpen()) {
                document.close();
            }
            throw new IllegalStateException("Failed to render marketing report PDF", e);
        }
    }

    /** A filesystem/header-safe download filename for the rendered report. */
    public String fileName(EntityMarketingReportResponse report) {
        String name = report.getEntity() != null && report.getEntity().getName() != null
                ? report.getEntity().getName() : "entity";
        String slug = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = "entity";
        }
        return "marketing-report-" + slug + ".pdf";
    }

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    private void addHeader(Document document, EntityMarketingReportResponse report) throws DocumentException {
        EntityDetailResponse entity = report.getEntity();
        String name = entity != null && entity.getName() != null ? entity.getName() : "Entity";

        PdfPTable banner = fullWidthTable(1);
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(BRAND);
        cell.setBorder(0);
        cell.setPadding(16f);

        cell.addElement(new Paragraph("MARKETING INTELLIGENCE REPORT", SUBTITLE));
        cell.addElement(new Paragraph(name, TITLE));

        StringBuilder meta = new StringBuilder();
        if (entity != null && entity.getType() != null) {
            meta.append(capitalize(entity.getType()));
        }
        if (entity != null && entity.getDirector() != null && !entity.getDirector().isBlank()) {
            appendSep(meta).append("Dir. ").append(entity.getDirector());
        }
        if (entity != null && entity.getReleaseDate() != null) {
            appendSep(meta).append("Releases ").append(entity.getReleaseDate());
        }
        if (meta.length() > 0) {
            cell.addElement(new Paragraph(meta.toString(), SUBTITLE));
        }
        banner.addCell(cell);
        document.add(banner);

        String stamp = report.getGeneratedAt() != null ? STAMP.format(report.getGeneratedAt())
                : STAMP.format(Instant.EPOCH);
        Paragraph sub = new Paragraph(
                "Generated " + stamp + (report.getPeriod() != null ? "  ·  Trend window: " + report.getPeriod() : ""),
                BODY_MUTED);
        sub.setSpacingBefore(6f);
        sub.setSpacingAfter(4f);
        document.add(sub);

        if (entity != null) {
            String cast = entity.getActors() != null && !entity.getActors().isEmpty()
                    ? String.join(", ", entity.getActors()) : null;
            if (cast != null) {
                document.add(labelled("Cast", cast));
            }
            String keywords = keywordList(entity.getKeywords());
            if (keywords != null) {
                document.add(labelled("Tracked keywords", keywords));
            }
        }
    }

    private void addHighlights(Document document, List<String> highlights) throws DocumentException {
        if (highlights == null || highlights.isEmpty()) {
            return;
        }
        sectionHeader(document, "Highlights");
        for (String h : highlights) {
            Paragraph p = new Paragraph();
            p.add(new Phrase("•  ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, ACCENT)));
            p.add(new Phrase(h, HIGHLIGHT));
            p.setSpacingAfter(4f);
            p.setIndentationLeft(6f);
            document.add(p);
        }
    }

    private void addHeadlineMetrics(Document document, HeadlineMetrics m) throws DocumentException {
        if (m == null) {
            return;
        }
        sectionHeader(document, "Headline Metrics");
        PdfPTable table = fullWidthTable(new float[]{1, 1, 1, 1});
        metricCard(table, "Total mentions", formatCount(m.getTotalMentions()));
        metricCard(table, "Positive sentiment", percent(m.getPositivityRatio()));
        metricCard(table, "Net sentiment", String.format(Locale.US, "%.1f : 1", m.getNetSentimentScore()));
        metricCard(table, "Platforms", String.valueOf(m.getPlatformsCovered()));
        metricCard(table, "Overall score", String.format(Locale.US, "%.2f", m.getOverallSentiment()));
        metricCard(table, "Positive", percent(m.getPositiveSentiment()));
        metricCard(table, "Neutral", percent(m.getNeutralSentiment()));
        metricCard(table, "Negative", percent(m.getNegativeSentiment()));
        document.add(table);
    }

    private void addCompetitivePositioning(Document document, CompetitivePositioning pos) throws DocumentException {
        if (pos == null || pos.getSnapshot() == null || pos.getSnapshot().isEmpty()) {
            return;
        }
        sectionHeader(document, "Competitive Positioning");

        String summary = pos.isLeadsCategory()
                ? String.format(Locale.US, "Category leader — ranked #1 of %d tracked titles on net sentiment.",
                        pos.getTotalTracked())
                : String.format(Locale.US, "Ranked #%d of %d tracked titles on net sentiment (leader: %s).",
                        pos.getRank(), pos.getTotalTracked(), pos.getLeaderName());
        Paragraph p = new Paragraph(summary, BODY_BOLD);
        p.setSpacingAfter(6f);
        document.add(p);

        PdfPTable table = fullWidthTable(new float[]{3, 1.4f, 1.4f, 1.4f, 1.4f});
        headerRow(table, "Title", "Mentions", "Positive", "Net sentiment", "Overall");
        int i = 0;
        for (CompetitorSnapshot s : pos.getSnapshot()) {
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            boolean isLeader = s.getEntityName() != null && s.getEntityName().equals(pos.getLeaderName());
            bodyCell(table, s.getEntityName() + (isLeader ? "  ★" : ""), bg, Element.ALIGN_LEFT, isLeader);
            bodyCell(table, formatCount(s.getTotalMentions()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, percent(s.getPositiveRatio()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.format(Locale.US, "%.1f", s.getNetSentimentScore()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.format(Locale.US, "%.2f", s.getOverallSentiment()), bg, Element.ALIGN_RIGHT, false);
        }
        document.add(table);
    }

    private void addPlatformReach(Document document, Map<String, Map<String, Long>> reach) throws DocumentException {
        if (reach == null || reach.isEmpty()) {
            return;
        }
        sectionHeader(document, "Platform Reach");
        PdfPTable table = fullWidthTable(new float[]{2, 1.2f, 1.2f, 1.2f, 1.2f});
        headerRow(table, "Platform", "Positive", "Negative", "Neutral", "Total");
        int i = 0;
        for (Map.Entry<String, Map<String, Long>> e : reach.entrySet()) {
            Map<String, Long> counts = e.getValue() != null ? e.getValue() : Map.of();
            long pos = sentiment(counts, "POSITIVE");
            long neg = sentiment(counts, "NEGATIVE");
            long neu = sentiment(counts, "NEUTRAL");
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, e.getKey(), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, String.valueOf(pos), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.valueOf(neg), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.valueOf(neu), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.valueOf(pos + neg + neu), bg, Element.ALIGN_RIGHT, true);
        }
        document.add(table);
    }

    private void addDefiningMoments(Document document,
                                    com.aura.service.dto.CheckpointImpactResponse impact) throws DocumentException {
        if (impact == null || impact.getImpacts() == null || impact.getImpacts().isEmpty()) {
            return;
        }
        sectionHeader(document, "Defining Moments");
        PdfPTable table = fullWidthTable(new float[]{1.2f, 2.4f, 1.4f, 1.6f, 1.4f});
        headerRow(table, "Date", "Moment", "Positivity Δ", "Net sentiment Δ", "Impact");
        int i = 0;
        for (var impactPoint : impact.getImpacts()) {
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, String.valueOf(impactPoint.getCheckpointDate()), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, impactPoint.getDescription(), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, signed(impactPoint.getPositiveRatioChange()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, signed(impactPoint.getNetSentimentChange()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.valueOf(impactPoint.getImpactDirection()), bg, Element.ALIGN_CENTER, false);
        }
        document.add(table);
    }

    private void addSentimentTrend(Document document, SentimentOverTimeResponse trend) throws DocumentException {
        if (trend == null || trend.getEntities() == null || trend.getEntities().isEmpty()) {
            return;
        }
        var series = trend.getEntities().get(0);
        if (series.getSentiments() == null || series.getSentiments().isEmpty()) {
            return;
        }
        sectionHeader(document, "Sentiment Trend");
        PdfPTable table = fullWidthTable(new float[]{2, 1.2f, 1.2f, 1.2f, 1.2f});
        headerRow(table, "Period", "Positive", "Negative", "Neutral", "Total");
        int i = 0;
        for (TimeSeriesData point : series.getSentiments()) {
            long total = point.getPositive() + point.getNegative() + point.getNeutral();
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, point.getDate(), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, String.valueOf(point.getPositive()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.valueOf(point.getNegative()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.valueOf(point.getNeutral()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.valueOf(total), bg, Element.ALIGN_RIGHT, true);
        }
        document.add(table);
    }

    private void addAuraMath(Document document, String status, JsonNode intelligence) throws DocumentException {
        sectionHeader(document, "AuraMath Intelligence");
        if (intelligence == null || !"ok".equals(status)) {
            Paragraph p = new Paragraph("AuraMath intelligence was unavailable when this report was generated.",
                    BODY_MUTED);
            document.add(p);
            return;
        }
        if (intelligence.isObject()) {
            PdfPTable table = fullWidthTable(new float[]{1.4f, 3});
            int i = 0;
            for (Iterator<String> it = intelligence.fieldNames(); it.hasNext(); ) {
                String field = it.next();
                JsonNode value = intelligence.get(field);
                Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
                bodyCell(table, prettyKey(field), bg, Element.ALIGN_LEFT, true);
                bodyCell(table, value != null && value.isValueNode() ? value.asText() : String.valueOf(value),
                        bg, Element.ALIGN_LEFT, false);
            }
            document.add(table);
        } else {
            document.add(new Paragraph(intelligence.toString(), BODY));
        }
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

    private Paragraph labelled(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Phrase(label + ": ", BODY_BOLD));
        p.add(new Phrase(value, BODY));
        p.setSpacingAfter(2f);
        return p;
    }

    private void metricCard(PdfPTable table, String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(8f);
        cell.setBorderColor(new Color(0xE0, 0xE4, 0xEC));
        Paragraph v = new Paragraph(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, ACCENT));
        Paragraph l = new Paragraph(label.toUpperCase(Locale.US),
                FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED));
        cell.addElement(v);
        cell.addElement(l);
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

    private void bodyCell(PdfPTable table, String text, Color bg, int align, boolean bold) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", bold ? BODY_BOLD : BODY));
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

    private static long sentiment(Map<String, Long> counts, String key) {
        Long v = counts.get(key);
        return v != null ? v : 0L;
    }

    private static String percent(double ratio) {
        return String.format(Locale.US, "%.0f%%", ratio * 100);
    }

    private static String signed(double v) {
        return String.format(Locale.US, "%+.2f", v);
    }

    private static String formatCount(long count) {
        if (count >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.US, "%.1fK", count / 1_000.0);
        }
        return String.valueOf(count);
    }

    private static String keywordList(List<KeywordDto> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (KeywordDto k : keywords) {
            if (k == null || k.getKeyword() == null || k.getKeyword().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k.getKeyword());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String prettyKey(String field) {
        String spaced = field.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return capitalize(spaced);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static StringBuilder appendSep(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append("  ·  ");
        }
        return sb;
    }
}
