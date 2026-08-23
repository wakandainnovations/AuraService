package com.aura.service.service;

import com.aura.service.dto.AiSummaryResponse;
import com.aura.service.dto.AudiencePulseAspectsResponse;
import com.aura.service.dto.AudiencePulseResponse;
import com.aura.service.dto.AuthorTypeBreakdownResponse;
import com.aura.service.dto.AuthorTypeCount;
import com.aura.service.dto.AwarenessResponse;
import com.aura.service.dto.BuzzResponse;
import com.aura.service.dto.CheckpointTrendPoint;
import com.aura.service.dto.CheckpointTrendResponse;
import com.aura.service.dto.CompetitorSnapshot;
import com.aura.service.dto.ContentIntentBreakdownResponse;
import com.aura.service.dto.ContentIntentCount;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityMarketingReportResponse.CompetitivePositioning;
import com.aura.service.dto.EntityMarketingReportResponse.HeadlineMetrics;
import com.aura.service.dto.HighlightItem;
import com.aura.service.dto.HourlyActivityResponse;
import com.aura.service.dto.KeywordDto;
import com.aura.service.dto.MomentumCausalReportResponse;
import com.aura.service.dto.MovieHealthResponse;
import com.aura.service.dto.PromotionalMixResponse;
import com.aura.service.dto.ReachResponse;
import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.dto.RecommendedActionItem;
import com.aura.service.dto.RecommendedActionsResponse;
import com.aura.service.dto.RegionBuzz;
import com.aura.service.dto.SentimentDeltaResponse;
import com.aura.service.dto.SentimentOverTimeResponse;
import com.aura.service.dto.TimeSeriesData;
import com.aura.service.dto.TodaysHighlightsResponse;
import com.aura.service.dto.TopSpreaderContent;
import com.aura.service.dto.TopSpreaderContentResponse;
import com.aura.service.dto.TopSpreaderInsightAction;
import com.aura.service.dto.TopSpreaderInsightsResponse;
import com.aura.service.dto.TopicCategoryBreakdownResponse;
import com.aura.service.dto.TopicCategoryCount;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * Renders an {@link EntityMarketingReportResponse} into a polished, prospect-facing PDF using
 * OpenPDF. The layout mirrors the JSON report end to end: a branded header, the deterministic
 * highlights, headline metrics, vitals (health/buzz/reach/awareness), competitive positioning,
 * platform reach, audience pulse (regional + aspect chips), content/audience mix breakdowns, posting
 * activity, defining moments, checkpoint trend, sentiment delta, a compact sentiment trend, top
 * spreaders and their AI collaboration insights, the recommended-actions roadmap, the AI narrative
 * summary / today's-highlights panel, the momentum & causal-chain intelligence report, and the
 * embedded AuraMath intelligence. Sections that are absent from the report (graceful degradation)
 * are simply skipped.
 */
@Slf4j
@Service
public class EntityMarketingReportPdfService {

    private static final Color BRAND = new Color(0x1F, 0x2D, 0x5A);     // deep navy
    private static final Color ACCENT = new Color(0x2E, 0x86, 0xDE);    // blue
    private static final Color ACCENT_SOFT = new Color(0xEA, 0xF2, 0xFC);
    private static final Color HEADER_BG = new Color(0x1F, 0x2D, 0x5A);
    private static final Color ROW_ALT = new Color(0xF2, 0xF5, 0xFA);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color POSITIVE = new Color(0x16, 0xA3, 0x6E);
    private static final Color NEUTRAL = new Color(0x94, 0x9C, 0xB0);
    private static final Color NEGATIVE = new Color(0xE1, 0x46, 0x46);
    private static final Color AMBER = new Color(0xD6, 0x9E, 0x2E);
    private static final Color GREEN_SOFT = new Color(0xE8, 0xF7, 0xF0);
    private static final Color AMBER_SOFT = new Color(0xFC, 0xF6, 0xE8);
    private static final Color RED_SOFT = new Color(0xFC, 0xEB, 0xEB);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Color.WHITE);
    private static final Font SUBTITLE = FontFactory.getFont(FontFactory.HELVETICA, 11, new Color(0xD5, 0xDD, 0xEE));
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, BRAND);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
    private static final Font BODY_MUTED = FontFactory.getFont(FontFactory.HELVETICA, 10, MUTED);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
    private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
    private static final Font HIGHLIGHT = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);
    private static final Font SUBSECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11.5f, ACCENT);
    private static final Font CALLOUT = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9.5f, Color.BLACK);
    private static final Font CARD_TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, Color.BLACK);
    private static final Font BAR_LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Color.WHITE);
    private static final Font PLAN_TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(0xC4, 0xC7, 0xF0));
    private static final Font PLAN_BODY = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.WHITE);

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
            addVitals(document, report);
            addCompetitivePositioning(document, report.getCompetitivePositioning());
            addPlatformReach(document, report.getPlatformReach());
            addAudiencePulse(document, report.getAudiencePulse(), report.getAudiencePulseAspects());
            addContentMix(document, report);
            addPostingActivity(document, report.getHourlyActivity());
            addDefiningMoments(document, report.getDefiningMoments());
            addCheckpointTrend(document, report.getCheckpointTrend());
            addSentimentDelta(document, report.getSentimentDelta());
            addSentimentTrend(document, report.getSentimentTrend());
            addTopSpreaders(document, report.getTopSpreaders(), report.getTopSpreaderInsights());
            addRecommendedActions(document, report.getRecommendedActions());
            addAiNarrative(document, report.getAiSummary(), report.getTodaysHighlights());
            addMomentumIntelligence(document, report.getMomentumIntelligence());
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

    /** Compact metric-card row for movie health, buzz, reach, and awareness — the report's "vitals". */
    private void addVitals(Document document, EntityMarketingReportResponse report) throws DocumentException {
        MovieHealthResponse health = report.getMovieHealth();
        BuzzResponse buzz = report.getBuzz();
        ReachResponse reach = report.getReach();
        AwarenessResponse awareness = report.getAwareness();
        if (health == null && buzz == null && reach == null && awareness == null) {
            return;
        }
        sectionHeader(document, "Vitals");
        PdfPTable table = fullWidthTable(new float[]{1, 1, 1, 1});
        if (health != null) {
            metricCard(table, "Movie health",
                    health.getHealthLabel() + "  (" + String.format(Locale.US, "%.0f%%", health.getHealthPercentage()) + ")");
        }
        if (buzz != null) {
            metricCard(table, "Buzz vs. yesterday",
                    signedPct(buzz.getMentionsChangePct()) + "  (" + formatCount(buzz.getMentionsToday()) + " today)");
        }
        if (reach != null) {
            metricCard(table, "Reach", formatCount(reach.getUniqueUsers()) + " unique users");
        }
        if (awareness != null) {
            metricCard(table, "Awareness",
                    awareness.getAwarenessLevel() + "  (" + formatCount(awareness.getTotalViews()) + " views)");
        }
        document.add(table);
    }

    private void addAudiencePulse(Document document, AudiencePulseResponse pulse, AudiencePulseAspectsResponse aspects)
            throws DocumentException {
        boolean hasRegions = pulse != null && pulse.getRegions() != null && !pulse.getRegions().isEmpty();
        boolean hasAspects = aspects != null
                && ((aspects.getPeopleLove() != null && !aspects.getPeopleLove().isEmpty())
                || (aspects.getPeopleConcerned() != null && !aspects.getPeopleConcerned().isEmpty()));
        if (!hasRegions && !hasAspects) {
            return;
        }
        sectionHeader(document, "Audience Pulse");

        if (hasRegions) {
            PdfPTable table = fullWidthTable(new float[]{0.6f, 2, 1.2f, 1.2f});
            headerRow(table, "#", "Region", "Mentions", "Share");
            int i = 0;
            for (RegionBuzz r : pulse.getRegions()) {
                if (i >= 8) {
                    break;
                }
                Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
                bodyCell(table, String.valueOf(r.getRank()), bg, Element.ALIGN_LEFT, false);
                bodyCell(table, r.getRegion(), bg, Element.ALIGN_LEFT, false);
                bodyCell(table, formatCount(r.getMentionCount()), bg, Element.ALIGN_RIGHT, false);
                bodyCell(table, pct0(r.getSharePct()), bg, Element.ALIGN_RIGHT, false);
            }
            document.add(table);
        }
        if (hasAspects) {
            if (aspects.getPeopleLove() != null && !aspects.getPeopleLove().isEmpty()) {
                subsectionHeader(document, "People Love");
                document.add(chipParagraph(aspects.getPeopleLove(), POSITIVE));
            }
            if (aspects.getPeopleConcerned() != null && !aspects.getPeopleConcerned().isEmpty()) {
                subsectionHeader(document, "People Concerned About");
                document.add(chipParagraph(aspects.getPeopleConcerned(), NEGATIVE));
            }
        }
    }

    private Paragraph chipParagraph(List<String> items, Color color) {
        Paragraph p = new Paragraph();
        Font chipFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, color);
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                p.add(new Phrase("   •   ", BODY_MUTED));
            }
            p.add(new Phrase(items.get(i), chipFont));
        }
        p.setSpacingAfter(6f);
        return p;
    }

    private void addContentMix(Document document, EntityMarketingReportResponse report) throws DocumentException {
        PromotionalMixResponse promo = report.getPromotionalMix();
        AuthorTypeBreakdownResponse authorTypes = report.getAuthorTypeBreakdown();
        ContentIntentBreakdownResponse intents = report.getContentIntentBreakdown();
        TopicCategoryBreakdownResponse topics = report.getTopicCategoryBreakdown();
        if (promo == null && authorTypes == null && intents == null && topics == null) {
            return;
        }
        sectionHeader(document, "Content & Audience Mix");

        if (promo != null && promo.getTotalPosts() > 0) {
            subsectionHeader(document, "Promotional vs. Organic");
            Paragraph p = new Paragraph(String.format(Locale.US,
                    "%s of %s posts are promotional (%s organic).",
                    pct0(promo.getPromotionalSharePct()), formatCount(promo.getTotalPosts()),
                    pct0(100.0 - promo.getPromotionalSharePct())), BODY);
            p.setSpacingAfter(6f);
            document.add(p);
        }
        addRankedShareTable(document, "Author Types", authorTypes == null ? null : authorTypes.getAuthorTypes(),
                AuthorTypeCount::getAuthorType, AuthorTypeCount::getCount, AuthorTypeCount::getSharePct);
        addRankedShareTable(document, "Content Intent", intents == null ? null : intents.getIntents(),
                ContentIntentCount::getContentIntent, ContentIntentCount::getCount, ContentIntentCount::getSharePct);
        addRankedShareTable(document, "Topic Categories", topics == null ? null : topics.getTopics(),
                TopicCategoryCount::getTopicCategory, TopicCategoryCount::getCount, TopicCategoryCount::getSharePct);
    }

    private <T> void addRankedShareTable(Document document, String title, List<T> items,
                                         Function<T, String> label, ToLongFunction<T> count,
                                         ToDoubleFunction<T> sharePct) throws DocumentException {
        if (items == null || items.isEmpty()) {
            return;
        }
        subsectionHeader(document, title);
        PdfPTable table = fullWidthTable(new float[]{3, 1.2f, 1.2f});
        headerRow(table, "Category", "Posts", "Share");
        int i = 0;
        for (T item : items) {
            if (i >= 6) {
                break;
            }
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, label.apply(item), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, formatCount(count.applyAsLong(item)), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, pct0(sharePct.applyAsDouble(item)), bg, Element.ALIGN_RIGHT, false);
        }
        document.add(table);
    }

    private void addPostingActivity(Document document, HourlyActivityResponse activity) throws DocumentException {
        if (activity == null) {
            return;
        }
        sectionHeader(document, "Posting Activity");
        Integer peakHour = null;
        long peakCount = -1;
        if (activity.getHourlyDistribution() != null) {
            for (Map.Entry<Integer, Long> e : activity.getHourlyDistribution().entrySet()) {
                if (e.getValue() != null && e.getValue() > peakCount) {
                    peakCount = e.getValue();
                    peakHour = e.getKey();
                }
            }
        }
        PdfPTable table = fullWidthTable(new float[]{1, 1});
        metricCard(table, "Active users", formatCount(activity.getTotalActiveUsers()));
        metricCard(table, "Peak hour (UTC)", peakHour != null
                ? String.format(Locale.US, "%02d:00  (%s posts)", peakHour, formatCount(peakCount)) : "—");
        document.add(table);
    }

    private void addCheckpointTrend(Document document, CheckpointTrendResponse trend) throws DocumentException {
        if (trend == null || trend.getTrendPoints() == null || trend.getTrendPoints().isEmpty()) {
            return;
        }
        sectionHeader(document, "Checkpoint Trend");
        PdfPTable table = fullWidthTable(new float[]{1.1f, 2.2f, 1.3f, 1.1f, 1.3f, 1.3f});
        headerRow(table, "Date", "Moment", "Mentions", "Positive", "Net sentiment", "Δ vs prior");
        int i = 0;
        for (CheckpointTrendPoint p : trend.getTrendPoints()) {
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, String.valueOf(p.getCheckpointDate()), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, p.getDescription(), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, formatCount(p.getPeriodMentions()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, percent(p.getPositiveRatio()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.format(Locale.US, "%.1f", p.getNetSentiment()), bg, Element.ALIGN_RIGHT, false);
            String delta = p.getNetSentimentChangeFromPrevious() != null
                    ? signed(p.getNetSentimentChangeFromPrevious()) : "—";
            bodyCell(table, delta, bg, Element.ALIGN_RIGHT, false);
        }
        document.add(table);
    }

    private void addSentimentDelta(Document document, SentimentDeltaResponse delta) throws DocumentException {
        if (delta == null) {
            return;
        }
        sectionHeader(document, "Sentiment Delta");
        Paragraph headline = new Paragraph(String.format(Locale.US, "%s (%s)  →  %s (%s)",
                delta.getFromDate(), orDash(delta.getFromLabel()), delta.getToDate(), orDash(delta.getToLabel())),
                BODY_MUTED);
        headline.setSpacingAfter(6f);
        document.add(headline);

        PdfPTable table = fullWidthTable(new float[]{1, 1, 1});
        metricCard(table, "Mentions Δ", String.format(Locale.US, "%+d", delta.getMentionsDelta()));
        metricCard(table, "Positive ratio Δ", signed(delta.getPositiveRatioDelta() * 100) + " pts");
        metricCard(table, "Net sentiment Δ", signed(delta.getNetSentimentDelta()));
        document.add(table);
    }

    private void addTopSpreaders(Document document, TopSpreaderContentResponse spreaders,
                                 TopSpreaderInsightsResponse insights) throws DocumentException {
        if (spreaders == null || spreaders.spreaders() == null || spreaders.spreaders().isEmpty()) {
            return;
        }
        sectionHeader(document, "Top Spreaders");
        PdfPTable table = fullWidthTable(new float[]{2.4f, 1.3f, 1.1f, 3.2f});
        headerRow(table, "Spreader", "Total views", "Posts", "Top post");
        int i = 0;
        for (TopSpreaderContent s : spreaders.spreaders()) {
            if (i >= 10) {
                break;
            }
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, orDash(s.globalUserId()), bg, Element.ALIGN_LEFT, true);
            bodyCell(table, formatCount(s.totalViews()), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, String.valueOf(s.topContent().size()), bg, Element.ALIGN_RIGHT, false);
            String topPost = s.topContent().isEmpty() ? "—" : truncate(s.topContent().get(0).content(), 90);
            bodyCell(table, topPost, bg, Element.ALIGN_LEFT, false);
        }
        document.add(table);

        if (insights != null && insights.summary() != null && !insights.summary().isBlank()) {
            subsectionHeader(document, "Collaboration Insights");
            Paragraph p = new Paragraph(insights.summary(), BODY);
            p.setSpacingAfter(6f);
            document.add(p);
            if (insights.actions() != null) {
                int a = 0;
                for (TopSpreaderInsightAction action : insights.actions()) {
                    if (a++ >= 8) {
                        break;
                    }
                    highlightCard(document, orDash(action.spreaderId()) + "   [" + action.impact() + "]",
                            action.action(), ACCENT_SOFT, ACCENT);
                }
            }
        }
    }

    private void addRecommendedActions(Document document, RecommendedActionsResponse actions) throws DocumentException {
        if (actions == null || actions.getActions() == null || actions.getActions().isEmpty()) {
            return;
        }
        sectionHeader(document, "Recommended Actions");
        int i = 0;
        for (RecommendedActionItem item : actions.getActions()) {
            if (i++ >= 15) {
                break;
            }
            String title = orDash(item.getTitle()) + "   [" + item.getCategory() + " · "
                    + item.getConfidencePct() + "% confidence]";
            highlightCard(document, title, item.getReason(), ACCENT_SOFT, ACCENT);
        }
    }

    private void addAiNarrative(Document document, AiSummaryResponse summary, TodaysHighlightsResponse highlights)
            throws DocumentException {
        boolean hasSummary = summary != null && summary.getSummary() != null && !summary.getSummary().isBlank();
        boolean hasHighlights = highlights != null && highlights.getHighlights() != null
                && !highlights.getHighlights().isEmpty();
        if (!hasSummary && !hasHighlights) {
            return;
        }
        sectionHeader(document, "AI Narrative Summary");
        if (hasSummary) {
            Paragraph p = new Paragraph(summary.getSummary(), BODY);
            p.setLeading(14f);
            p.setSpacingAfter(8f);
            document.add(p);
        }
        if (hasHighlights) {
            for (HighlightItem h : highlights.getHighlights()) {
                boolean positive = "POSITIVE".equalsIgnoreCase(h.getType());
                boolean negative = "NEGATIVE".equalsIgnoreCase(h.getType());
                Color stripe = positive ? POSITIVE : negative ? NEGATIVE : NEUTRAL;
                Color soft = positive ? GREEN_SOFT : negative ? RED_SOFT : ROW_ALT;
                highlightCard(document, h.getType(), h.getText(), soft, stripe);
            }
        }
    }

    private void addMomentumIntelligence(Document document, MomentumCausalReportResponse momentum)
            throws DocumentException {
        if (momentum == null) {
            return;
        }
        sectionHeader(document, "Momentum & Causal Chain Intelligence");

        subsectionHeader(document, "Viewership Momentum (VMI)");
        addJsonOrInsufficient(document, momentum.getVmiTrend());

        subsectionHeader(document, "Causal Chains");
        addJsonOrInsufficient(document, momentum.getCausalChains());

        addCausalLiftUsers(document, momentum.getTopCausalLiftUsers());
        addStatisticalCandidates(document, "Non-Obvious Levers", momentum.getNonObviousLevers());
        addStatisticalCandidates(document, "Playbook Matches", momentum.getPlaybookMatches());
    }

    /** Renders a verbatim AuraMath JSON section: its insufficient-history envelope, or a readable key/value table. */
    private void addJsonOrInsufficient(Document document, JsonNode node) throws DocumentException {
        if (node == null) {
            document.add(new Paragraph("Not available.", BODY_MUTED));
            return;
        }
        if ("insufficient_history".equals(text(node, "status"))) {
            document.add(new Paragraph(orDash(text(node, "details")), BODY_MUTED));
            return;
        }
        if (!node.isObject() || node.size() == 0) {
            document.add(new Paragraph(summarize(node), BODY));
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            kv(rows, prettyKey(field), summarize(node.get(field)));
        }
        addKvTable(document, rows);
    }

    private void addCausalLiftUsers(Document document, MomentumCausalReportResponse.TopCausalLiftUsersSection section)
            throws DocumentException {
        if (section == null) {
            return;
        }
        subsectionHeader(document, "Top Causal-Lift Users");
        if (!"ok".equals(section.getStatus()) || section.getUsers() == null || section.getUsers().isEmpty()) {
            document.add(new Paragraph(orDash(section.getDetails()), BODY_MUTED));
            return;
        }
        PdfPTable table = fullWidthTable(new float[]{2, 1.2f, 1.1f, 1.2f, 1.2f});
        headerRow(table, "User", "Causal lift", "Confidence", "Mentions", "Engagement");
        int i = 0;
        for (var u : section.getUsers()) {
            if (i >= 10) {
                break;
            }
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, orDash(u.getGlobalUserId()), bg, Element.ALIGN_LEFT, true);
            bodyCell(table, u.getCausalLiftScore() != null
                    ? String.format(Locale.US, "%.2f", u.getCausalLiftScore()) : "—", bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, orDash(u.getConfidence()), bg, Element.ALIGN_CENTER, false);
            bodyCell(table, u.getMentionCount() != null ? formatCount(u.getMentionCount()) : "—",
                    bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, u.getEngagementRating() != null
                    ? String.format(Locale.US, "%.2f", u.getEngagementRating()) : "—", bg, Element.ALIGN_RIGHT, false);
        }
        document.add(table);
    }

    private void addStatisticalCandidates(Document document, String title,
                                          MomentumCausalReportResponse.StatisticalCandidateSection section)
            throws DocumentException {
        if (section == null) {
            return;
        }
        subsectionHeader(document, title);
        if (!"ok".equals(section.getStatus()) || section.getCandidates() == null || section.getCandidates().isEmpty()) {
            document.add(new Paragraph(orDash(section.getDetails()), BODY_MUTED));
            return;
        }
        for (RecommendedActionCandidate c : section.getCandidates()) {
            String cardTitle = orDash(c.factorName()) + "   [" + c.confidencePct() + "% confidence]";
            String detail = c.supportingFacts() == null ? "" : String.join(" ", c.supportingFacts());
            highlightCard(document, cardTitle, detail, ACCENT_SOFT, ACCENT);
        }
    }

    /**
     * Renders the embedded AuraMath entity-report with the same prospect-facing treatment the
     * upstream {@code GET /api/marketing/entity-report/{id}/pdf} endpoint uses — structured
     * subsections, tables, and callout cards — instead of dumping raw JSON. Sections the upstream
     * did not include are skipped; fields this renderer does not recognise fall back to a
     * humanized key/value table so new upstream sections still surface.
     */
    private void addAuraMath(Document document, String status, JsonNode intelligence) throws DocumentException {
        sectionHeader(document, "AuraMath Intelligence");
        if (intelligence == null || !"ok".equals(status)) {
            document.add(new Paragraph("AuraMath intelligence was unavailable when this report was generated.",
                    BODY_MUTED));
            return;
        }
        if (!intelligence.isObject()) {
            document.add(new Paragraph(summarize(intelligence), BODY));
            return;
        }
        // Degraded upstream shapes (entity not found / no history) carry a plain message
        // instead of the section payload.
        if (intelligence.has("message") && !intelligence.has("entityProfile")) {
            document.add(new Paragraph(intelligence.get("message").asText(), BODY_MUTED));
            return;
        }

        addAuraMathEntityProfile(document, intelligence.get("entityProfile"));
        addAuraMathConversation(document, intelligence.get("conversationProfile"));
        addAuraMathTopics(document, intelligence.get("topicIntelligence"));
        addAuraMathSentiment(document, intelligence.get("audienceSentiment"));
        addAuraMathChannels(document, intelligence.get("channelStrategy"));
        addAuraMathAdvocates(document, intelligence.get("topAdvocates"));
        addAuraMathOpportunities(document, intelligence.get("opportunityFlags"));
        addAuraMathRecommendations(document, intelligence.get("marketingRecommendations"));
        addAuraMathRedFlags(document, intelligence.get("redFlags"));
        addAuraMathOtherFields(document, intelligence);
    }

    /** Top-level entity-report fields rendered by a dedicated subsection (or intentionally skipped). */
    private static final Set<String> AURAMATH_KNOWN_FIELDS = Set.of(
            "entityProfile", "conversationProfile", "topicIntelligence", "audienceSentiment",
            "channelStrategy", "topAdvocates", "marketingRecommendations", "redFlags",
            "opportunityFlags", "generatedAt", "entityId", "message");

    private void addAuraMathEntityProfile(Document document, JsonNode p) throws DocumentException {
        if (p == null || !p.isObject()) {
            return;
        }
        subsectionHeader(document, "Entity Profile");
        List<String[]> rows = new ArrayList<>();
        kv(rows, "Type", capitalize(text(p, "type")));
        kv(rows, "Tracked keywords", joinArray(p.get("trackedKeywords")));
        kv(rows, "Active platforms", joinArray(p.get("activePlatforms")));
        kv(rows, "Posts analysed", text(p, "totalPosts"));
        String audience = text(p, "audienceSize");
        kv(rows, "Audience size", audience != null ? audience + " distinct authors" : null);
        String first = text(p, "firstSeen");
        String last = text(p, "lastSeen");
        kv(rows, "Observation window", first != null && last != null ? first + "  —  " + last : null);
        String span = text(p, "observationSpanDays");
        String perDay = text(p, "averagePostsPerDay");
        kv(rows, "Span", span != null
                ? span + " days" + (perDay != null ? "  (" + perDay + " posts/day)" : "") : null);
        kv(rows, "Virality tier", text(p, "viralityTier"));
        addKvTable(document, rows);
        callout(document, text(p, "viralityTierExplained"));
    }

    private void addAuraMathConversation(Document document, JsonNode c) throws DocumentException {
        if (c == null || !c.isObject()) {
            return;
        }
        subsectionHeader(document, "Virality & Conversation Dynamics");
        String explained = text(c, "amplificationExplained");
        if (explained != null) {
            Paragraph p = new Paragraph(explained, BODY);
            p.setSpacingAfter(6f);
            document.add(p);
        }
        List<String[]> rows = new ArrayList<>();
        kv(rows, "Branching ratio", text(c, "branchingRatio"));
        kv(rows, "Distinct burst events", text(c, "distinctBurstEvents"));
        kv(rows, "Most active day", text(c, "mostActiveDayOfWeek"));
        kv(rows, "Peak activity windows", joinArray(c.get("peakActivityWindows")));
        addKvTable(document, rows);
        JsonNode burst = c.get("longestBurst");
        if (burst != null && burst.isObject()) {
            String desc = text(burst, "readableDescription");
            callout(document, desc != null ? "Largest burst observed: " + desc : null);
        }
    }

    private void addAuraMathTopics(Document document, JsonNode topics) throws DocumentException {
        if (topics == null || !topics.isArray() || topics.isEmpty()) {
            return;
        }
        subsectionHeader(document, "Topic Intelligence");
        PdfPTable table = fullWidthTable(new float[]{1.8f, 1, 0.9f, 1.2f, 2.6f});
        headerRow(table, "Keyword", "Mentions", "Bursts", "Tone", "Excitation profile");
        int i = 0;
        for (JsonNode topic : topics) {
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, orDash(text(topic, "keyword")), bg, Element.ALIGN_LEFT, true);
            bodyCell(table, orDash(text(topic, "totalMentions")), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, orDash(text(topic, "burstsTriggered")), bg, Element.ALIGN_RIGHT, false);
            toneCell(table, text(topic, "dominantTone"), bg);
            bodyCell(table, orDash(text(topic, "excitationProfile")), bg, Element.ALIGN_LEFT, false);
        }
        document.add(table);
    }

    private void addAuraMathSentiment(Document document, JsonNode s) throws DocumentException {
        if (s == null || !s.isObject()) {
            return;
        }
        subsectionHeader(document, "Audience Sentiment");
        String label = text(s, "sentimentLabel");
        String net = text(s, "netSentiment");
        if (label != null) {
            double netValue = s.path("netSentiment").asDouble(0);
            Paragraph head = new Paragraph();
            head.add(new Phrase(label, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, sentimentColor(netValue))));
            if (net != null) {
                head.add(new Phrase("    (net sentiment " + net + ")", BODY_MUTED));
            }
            head.setSpacingAfter(6f);
            document.add(head);
        }
        JsonNode tones = s.get("toneBreakdown");
        if (tones != null && tones.isObject()) {
            long pos = tones.path("positive").asLong(0);
            long neu = tones.path("neutral").asLong(0);
            long neg = tones.path("negative").asLong(0);
            if (pos + neu + neg > 0) {
                document.add(toneBar(pos, neu, neg));
            }
        }
    }

    private void addAuraMathChannels(Document document, JsonNode ch) throws DocumentException {
        if (ch == null || !ch.isObject()) {
            return;
        }
        subsectionHeader(document, "Channel Strategy");
        String headline = text(ch, "headline");
        if (headline != null) {
            Paragraph p = new Paragraph(headline, BODY_BOLD);
            p.setSpacingAfter(6f);
            document.add(p);
        }
        JsonNode channels = ch.get("channels");
        if (channels == null || !channels.isArray() || channels.isEmpty()) {
            return;
        }
        PdfPTable table = fullWidthTable(new float[]{2, 1, 1.4f});
        headerRow(table, "Platform", "Posts", "Share of conversation");
        int i = 0;
        for (JsonNode c : channels) {
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, orDash(text(c, "platform")), bg, Element.ALIGN_LEFT, true);
            bodyCell(table, orDash(text(c, "postCount")), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, percent(c.path("share").asDouble(0)), bg, Element.ALIGN_RIGHT, false);
        }
        document.add(table);
    }

    private void addAuraMathAdvocates(Document document, JsonNode advocates) throws DocumentException {
        if (advocates == null || !advocates.isArray() || advocates.isEmpty()) {
            return;
        }
        subsectionHeader(document, "Top Advocates");
        Paragraph note = new Paragraph(
                "The highest-amplification voices already driving this conversation — natural seeding targets.",
                BODY_MUTED);
        note.setSpacingAfter(5f);
        document.add(note);

        PdfPTable table = fullWidthTable(new float[]{2.2f, 1.6f, 0.9f, 1.2f, 1});
        headerRow(table, "Author", "Segment", "Posts", "Engagement", "Influence");
        int i = 0;
        for (JsonNode a : advocates) {
            if (i >= 8) {
                break;
            }
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, advocateHandle(a), bg, Element.ALIGN_LEFT, true);
            bodyCell(table, orDash(text(a, "tribe_label")), bg, Element.ALIGN_LEFT, false);
            bodyCell(table, orDash(text(a, "post_count")), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, orDash(text(a, "total_engagement")), bg, Element.ALIGN_RIGHT, false);
            bodyCell(table, a.hasNonNull("hawkes_alpha")
                            ? String.format(Locale.US, "%.2f", a.get("hawkes_alpha").asDouble())
                            : "—",
                    bg, Element.ALIGN_RIGHT, false);
        }
        document.add(table);
    }

    private void addAuraMathOpportunities(Document document, JsonNode opps) throws DocumentException {
        if (opps == null || !opps.isArray() || opps.isEmpty()) {
            return;
        }
        subsectionHeader(document, "Why Now — Opportunities");
        for (JsonNode op : opps) {
            highlightCard(document, orDash(text(op, "opportunity")), text(op, "detail"), GREEN_SOFT, POSITIVE);
        }
    }

    private void addAuraMathRecommendations(Document document, JsonNode r) throws DocumentException {
        if (r == null || !r.isObject()) {
            return;
        }
        subsectionHeader(document, "Recommended Play");
        List<String[]> rows = new ArrayList<>();
        kv(rows, "Primary channel", text(r, "primaryChannel"));
        kv(rows, "Best time to engage", text(r, "bestTimeToEngage"));
        kv(rows, "Campaign type", text(r, "campaignType"));
        kv(rows, "Amplification potential", text(r, "amplificationPotential"));
        kv(rows, "Estimated reach", text(r, "estimatedReachMultiplier"));
        kv(rows, "Addressable audience", text(r, "addressableAudience"));
        kv(rows, "Content triggers", joinArray(r.get("contentTriggers")));
        kv(rows, "Content strategy", text(r, "contentStrategy"));
        addKvTable(document, rows);

        String advice = text(r, "actionableAdvice");
        if (advice != null) {
            PdfPTable box = fullWidthTable(1);
            box.setSpacingBefore(8f);
            PdfPCell cell = new PdfPCell();
            cell.setBackgroundColor(BRAND);
            cell.setBorder(0);
            cell.setPadding(12f);
            Paragraph title = new Paragraph("YOUR ACTION PLAN", PLAN_TITLE);
            title.setSpacingAfter(4f);
            cell.addElement(title);
            Paragraph body = new Paragraph(advice, PLAN_BODY);
            body.setLeading(14f);
            cell.addElement(body);
            box.addCell(cell);
            document.add(box);
        }
    }

    private void addAuraMathRedFlags(Document document, JsonNode flags) throws DocumentException {
        if (flags == null || !flags.isArray() || flags.isEmpty()) {
            return;
        }
        subsectionHeader(document, "Considerations");
        for (JsonNode fl : flags) {
            String severity = text(fl, "severity");
            String sev = severity != null ? severity.toUpperCase(Locale.US) : "LOW";
            Color soft = "HIGH".equals(sev) ? RED_SOFT : "MEDIUM".equals(sev) ? AMBER_SOFT : ROW_ALT;
            Color stripe = "HIGH".equals(sev) ? NEGATIVE : "MEDIUM".equals(sev) ? AMBER : NEUTRAL;
            highlightCard(document, orDash(text(fl, "flag")) + "   [" + sev + "]", text(fl, "detail"), soft, stripe);
        }
    }

    /** Forward-compatibility: surface upstream fields this renderer doesn't know as readable key/values. */
    private void addAuraMathOtherFields(Document document, JsonNode intelligence) throws DocumentException {
        List<String[]> rows = new ArrayList<>();
        for (Iterator<String> it = intelligence.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (AURAMATH_KNOWN_FIELDS.contains(field)) {
                continue;
            }
            kv(rows, prettyKey(field), summarize(intelligence.get(field)));
        }
        if (!rows.isEmpty()) {
            subsectionHeader(document, "Additional Intelligence");
            addKvTable(document, rows);
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

    private void subsectionHeader(Document document, String title) throws DocumentException {
        Paragraph p = new Paragraph(title, SUBSECTION);
        p.setSpacingBefore(10f);
        p.setSpacingAfter(4f);
        document.add(p);
    }

    /** Accent-striped italic note used for upstream explanations (virality tier, largest burst). */
    private void callout(Document document, String textValue) throws DocumentException {
        if (textValue == null || textValue.isBlank()) {
            return;
        }
        PdfPTable table = fullWidthTable(new float[]{1.2f, 98.8f});
        table.setSpacingBefore(6f);
        PdfPCell stripe = new PdfPCell();
        stripe.setBackgroundColor(ACCENT);
        stripe.setBorder(0);
        table.addCell(stripe);
        PdfPCell cell = new PdfPCell(new Phrase(textValue, CALLOUT));
        cell.setBackgroundColor(ACCENT_SOFT);
        cell.setBorder(0);
        cell.setPadding(9f);
        table.addCell(cell);
        document.add(table);
    }

    /** Colour-striped title + detail card used for opportunities and red flags. */
    private void highlightCard(Document document, String title, String detail, Color bg, Color stripeColor)
            throws DocumentException {
        PdfPTable table = fullWidthTable(new float[]{1.4f, 98.6f});
        table.setSpacingBefore(6f);
        PdfPCell stripe = new PdfPCell();
        stripe.setBackgroundColor(stripeColor);
        stripe.setBorder(0);
        table.addCell(stripe);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(bg);
        cell.setBorder(0);
        cell.setPadding(10f);
        Paragraph head = new Paragraph(title, CARD_TITLE);
        head.setSpacingAfter(3f);
        cell.addElement(head);
        if (detail != null && !detail.isBlank()) {
            Paragraph body = new Paragraph(detail, BODY);
            body.setLeading(13f);
            cell.addElement(body);
        }
        table.addCell(cell);
        document.add(table);
    }

    /** Horizontal stacked proportion bar for the positive/neutral/negative tone split. */
    private PdfPTable toneBar(long pos, long neu, long neg) {
        long total = pos + neu + neg;
        long[] counts = {pos, neu, neg};
        Color[] colors = {POSITIVE, NEUTRAL, NEGATIVE};
        String[] labels = {"Positive", "Neutral", "Negative"};
        int segments = 0;
        for (long c : counts) {
            if (c > 0) {
                segments++;
            }
        }
        float[] widths = new float[segments];
        int idx = 0;
        for (long c : counts) {
            if (c > 0) {
                widths[idx++] = c;
            }
        }
        PdfPTable bar = fullWidthTable(widths);
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) {
                continue;
            }
            int pct = (int) Math.round(100.0 * counts[i] / total);
            PdfPCell cell = new PdfPCell(new Phrase(labels[i] + "  " + pct + "%", BAR_LABEL));
            cell.setBackgroundColor(colors[i]);
            cell.setBorder(0);
            cell.setPadding(6f);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setMinimumHeight(20f);
            bar.addCell(cell);
        }
        return bar;
    }

    /** Renders the accumulated label/value pairs as a zebra-striped two-column table. */
    private void addKvTable(Document document, List<String[]> rows) throws DocumentException {
        if (rows.isEmpty()) {
            return;
        }
        PdfPTable table = fullWidthTable(new float[]{1.4f, 3});
        int i = 0;
        for (String[] row : rows) {
            Color bg = (i++ % 2 == 0) ? Color.WHITE : ROW_ALT;
            bodyCell(table, row[0], bg, Element.ALIGN_LEFT, true);
            bodyCell(table, row[1], bg, Element.ALIGN_LEFT, false);
        }
        document.add(table);
    }

    private static void kv(List<String[]> rows, String label, String value) {
        if (value != null && !value.isBlank()) {
            rows.add(new String[]{label, value});
        }
    }

    private void toneCell(PdfPTable table, String tone, Color bg) {
        Color color = tone == null ? Color.BLACK
                : "positive".equalsIgnoreCase(tone) ? POSITIVE
                : "negative".equalsIgnoreCase(tone) ? NEGATIVE
                : NEUTRAL;
        PdfPCell cell = new PdfPCell(new Phrase(capitalize(orDash(tone)),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, color)));
        cell.setBackgroundColor(bg);
        cell.setPadding(5f);
        cell.setBorderColor(new Color(0xE6, 0xE9, 0xF0));
        table.addCell(cell);
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

    /** Same as {@link #percent}, but for a value that's already a 0..100 percentage, not a 0..1 ratio. */
    private static String pct0(double alreadyPercent) {
        return String.format(Locale.US, "%.0f%%", alreadyPercent);
    }

    private static String signedPct(double alreadyPercent) {
        return String.format(Locale.US, "%+.0f%%", alreadyPercent);
    }

    private static String signed(double v) {
        return String.format(Locale.US, "%+.2f", v);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "—";
        }
        String trimmed = text.strip();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars).strip() + "...";
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

    /** The text of a value-node field, or {@code null} when missing, null, or not a value node. */
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

    /** Comma-joins an array of value nodes, or {@code null} when absent/empty. */
    private static String joinArray(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : array) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item.isValueNode() ? item.asText() : summarize(item));
        }
        return sb.toString();
    }

    /** A readable plain-text rendering of an arbitrary node — never raw JSON syntax. */
    private static String summarize(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        StringBuilder sb = new StringBuilder();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(summarize(item));
            }
            return sb.toString();
        }
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(prettyKey(field)).append(": ").append(summarize(node.get(field)));
        }
        return sb.toString();
    }

    private static Color sentimentColor(double net) {
        if (net >= 0.05) {
            return POSITIVE;
        }
        if (net <= -0.05) {
            return NEGATIVE;
        }
        return NEUTRAL;
    }

    /**
     * A readable author handle from an advocate entry — primary-platform profile URL reduced to
     * {@code @handle}, falling back to the raw URL, then the global user id.
     */
    private static String advocateHandle(JsonNode advocate) {
        JsonNode handles = advocate.get("platform_handles");
        if (handles != null && handles.isObject()) {
            JsonNode byPlatform = handles.get("by_platform");
            if (byPlatform == null || !byPlatform.isObject()) {
                byPlatform = handles;
            }
            String primary = text(handles, "primary_platform");
            JsonNode selected = primary != null ? byPlatform.get(primary) : null;
            if (selected == null && byPlatform.fieldNames().hasNext()) {
                selected = byPlatform.get(byPlatform.fieldNames().next());
            }
            if (selected != null && selected.isObject()) {
                String url = text(selected, "profile_url");
                String handle = handleFromProfileUrl(url);
                if (handle != null) {
                    return handle;
                }
                if (url != null) {
                    return url;
                }
            } else if (selected != null && selected.isValueNode() && !selected.asText().isBlank()) {
                // Legacy flat shape: values are plain handle strings.
                return selected.asText();
            }
        }
        return orDash(text(advocate, "global_user_id"));
    }

    /** Derive "@handle" from a profile URL, e.g. https://twitter.com/mmcLondon → @mmcLondon. */
    private static String handleFromProfileUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        int cut = u.indexOf('?');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        cut = u.indexOf('#');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        String last = u.substring(u.lastIndexOf('/') + 1);
        if (last.startsWith("@")) {
            last = last.substring(1);
        }
        if (last.isEmpty() || last.contains(".")) {
            return null;
        }
        return "@" + last;
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
