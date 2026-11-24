package com.aura.service.service;

import com.aura.service.dto.RightsValuationReportResponse;
import com.aura.service.dto.RightsValuationReportResponse.CohortOutcomes;
import com.aura.service.dto.RightsValuationReportResponse.ComparableFilm;
import com.aura.service.dto.RightsValuationReportResponse.MetricComparison;
import com.aura.service.dto.RightsValuationReportResponse.MovieProfile;
import com.aura.service.dto.RightsValuationReportResponse.PeerCohort;
import com.aura.service.dto.RightsValuationReportResponse.PlatformReach;
import com.aura.service.dto.RightsValuationReportResponse.SocialReach;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link RightsValuationReportResponse} as the PDF a production house hands to an OTT or
 * satellite buyer: movie facts, the selling points, every peer cohort (what its films earned, how this
 * film ranks, the closest comparable films), live social reach and the methodology. For an upcoming
 * movie the header says so and the pre-release buzz comes before the cohorts. The internal negotiation watch-outs are left out on
 * purpose. The standard PDF fonts have no rupee glyph, so "₹" is written as "Rs.".
 */
@Service
public class RightsValuationReportPdfService {

    private static final Color BRAND = new Color(0x1F, 0x2D, 0x5A);
    private static final Color ACCENT = new Color(0x2E, 0x86, 0xDE);
    private static final Color ROW_ALT = new Color(0xF2, 0xF5, 0xFA);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color POSITIVE = new Color(0x16, 0xA3, 0x6E);
    private static final Color GREEN_SOFT = new Color(0xE8, 0xF7, 0xF0);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Color.WHITE);
    private static final Font SUBTITLE = FontFactory.getFont(FontFactory.HELVETICA, 11, new Color(0xD5, 0xDD, 0xEE));
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, BRAND);
    private static final Font SUBSECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11.5f, ACCENT);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, Color.BLACK);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, Color.BLACK);
    private static final Font BODY_MUTED = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);
    private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Color.WHITE);
    private static final Font STRONG = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, POSITIVE);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US);

    public byte[] render(RightsValuationReportResponse report) {
        Document document = new Document(PageSize.A4, 40, 40, 44, 44);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            addHeader(document, report);
            addSellingPoints(document, report.sellingPoints());
            addProfile(document, report.movie(), report.currency());
            boolean upcoming = isUpcoming(report);
            if (upcoming) {
                // Before release, buzz is the movie's own evidence; lead with it.
                addSocialReach(document, report.socialReach(), report.currency(), true);
            }
            for (PeerCohort cohort : report.peerCohorts()) {
                addCohort(document, cohort, report.currency(), upcoming);
            }
            if (!upcoming) {
                addSocialReach(document, report.socialReach(), report.currency(), false);
            }
            addNotes(document, report.methodologyNotes());
            document.close();
        } catch (DocumentException ex) {
            throw new IllegalStateException("Could not render the rights-valuation PDF", ex);
        }
        return out.toByteArray();
    }

    public String fileName(RightsValuationReportResponse report) {
        String slug = report.movie().name() == null ? "movie"
                : report.movie().name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return (slug.isEmpty() ? "movie" : slug) + "-rights-valuation-report.pdf";
    }

    // ---- sections ------------------------------------------------------------------------------

    private void addHeader(Document document, RightsValuationReportResponse report) throws DocumentException {
        MovieProfile movie = report.movie();
        PdfPTable banner = new PdfPTable(1);
        banner.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(BRAND);
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setPadding(16);
        cell.addElement(new Paragraph(safe(movie.name()), TITLE));
        cell.addElement(new Paragraph(isUpcoming(report)
                ? "Pre-release Rights Valuation Report: buzz, talent track record and comparable films"
                : "Rights Valuation Report: performance against comparable films", SUBTITLE));
        List<String> facts = new ArrayList<>();
        if (movie.language() != null) {
            facts.add(movie.language());
        }
        if (movie.genre() != null) {
            facts.add(movie.genre());
        }
        if (movie.releaseDate() != null) {
            facts.add(isUpcoming(report)
                    ? "Releases " + DAY.format(movie.releaseDate())
                            + (movie.daysToRelease() == null ? "" : " (" + movie.daysToRelease() + " days away)")
                    : "Released " + DAY.format(movie.releaseDate()));
        } else if (isUpcoming(report)) {
            facts.add("Release date to be announced");
        }
        facts.add("Generated " + STAMP.format(report.generatedAt()));
        cell.addElement(new Paragraph(safe(String.join("  |  ", facts)), SUBTITLE));
        banner.addCell(cell);
        document.add(banner);
    }

    private void addSellingPoints(Document document, List<String> points) throws DocumentException {
        if (points == null || points.isEmpty()) {
            return;
        }
        section(document, "Why this film");
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(GREEN_SOFT);
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setPadding(10);
        for (String point : points) {
            Paragraph p = new Paragraph();
            p.add(new Phrase("+  ", STRONG));
            p.add(new Phrase(safe(point), BODY));
            p.setSpacingAfter(4);
            cell.addElement(p);
        }
        box.addCell(cell);
        document.add(box);
    }

    private void addProfile(Document document, MovieProfile m, String currency) throws DocumentException {
        section(document, "Film at a glance");
        PdfPTable table = new PdfPTable(new float[]{1.3f, 2f, 1.3f, 2f});
        table.setWidthPercentage(100);
        List<String[]> rows = new ArrayList<>();
        addFact(rows, "Budget", money(m.budget(), currency));
        addFact(rows, "Worldwide gross", money(m.worldwideGross(), currency));
        addFact(rows, "Return on budget", ratio(m.returnOnBudget()));
        addFact(rows, "Audience rating", m.audienceRating() == null ? null : String.format(Locale.US, "%.1f/10", m.audienceRating()));
        addFact(rows, "Trailer views", count(m.trailerViews()));
        addFact(rows, "Teaser views", count(m.teaserViews()));
        addFact(rows, "Song views", count(m.songViews()));
        addFact(rows, "Trailer comments", count(m.trailerComments()));
        addFact(rows, "Release screens", m.screens() == null ? null : String.valueOf(m.screens()));
        addFact(rows, "Runtime", m.runtimeMinutes() == null ? null : m.runtimeMinutes() + " min");
        addFact(rows, "Certification", m.cbfcRating());
        addFact(rows, "Released in", m.releaseLanguages() == null || m.releaseLanguages().isEmpty()
                ? null : String.join(", ", m.releaseLanguages()));
        addFact(rows, "Lead actor", m.leadActor());
        addFact(rows, "Director", m.director());
        addFact(rows, "Music director", m.musicDirector());
        addFact(rows, "Production", m.productionCompanies());
        if (rows.size() % 2 == 1) {
            rows.add(new String[]{"", ""});
        }
        for (String[] row : rows) {
            table.addCell(cell(row[0], BODY_MUTED, null));
            table.addCell(cell(row[1], BODY_BOLD, null));
        }
        document.add(table);
    }

    private void addCohort(Document document, PeerCohort cohort, String currency, boolean upcoming)
            throws DocumentException {
        section(document, cohort.label() + " (" + cohort.peerCount() + " films)");
        Paragraph criteria = new Paragraph(safe(cohort.criteria()), BODY_MUTED);
        criteria.setSpacingAfter(6);
        document.add(criteria);
        addOutcomes(document, cohort.outcomes(), currency);
        if (!cohort.metrics().isEmpty()) {
            if (upcoming) {
                Paragraph sub = new Paragraph("This film's video reach so far vs the peers' lifetime totals", SUBSECTION);
                sub.setSpacingBefore(8);
                sub.setSpacingAfter(4);
                document.add(sub);
            }
            metricTable(document, cohort.metrics(), currency);
        }

        if (!cohort.closestPeers().isEmpty()) {
            Paragraph sub = new Paragraph("Closest comparable films", SUBSECTION);
            sub.setSpacingBefore(8);
            sub.setSpacingAfter(4);
            document.add(sub);
            PdfPTable table = new PdfPTable(new float[]{2.6f, 1.3f, 1.4f, 1.4f, 0.9f, 0.8f, 0.8f, 1.1f});
            table.setWidthPercentage(100);
            header(table, "Film", "Released", "Budget", "Gross", "ROI", "Rating", "Verdict", "Video views");
            int i = 0;
            for (ComparableFilm f : cohort.closestPeers()) {
                Color bg = i++ % 2 == 1 ? ROW_ALT : null;
                String title = f.title() + (Boolean.TRUE.equals(f.outgrossedByThisMovie()) ? "  (out-grossed)" : "");
                table.addCell(cell(title, BODY, bg));
                table.addCell(cell(f.releaseDate() == null ? null : DAY.format(f.releaseDate()), BODY, bg));
                table.addCell(cell(money(f.budget(), currency), BODY, bg));
                table.addCell(cell(money(f.worldwideGross(), currency), BODY, bg));
                table.addCell(cell(ratio(f.returnOnBudget()), BODY, bg));
                table.addCell(cell(f.audienceRating() == null ? null : String.format(Locale.US, "%.1f", f.audienceRating()), BODY, bg));
                table.addCell(cell(f.verdict() == null ? null : capitalize(f.verdict()), BODY, bg));
                table.addCell(cell(count(f.totalVideoViews()), BODY, bg));
            }
            document.add(table);
        }
    }

    private void addSocialReach(Document document, SocialReach s, String currency, boolean upcoming)
            throws DocumentException {
        if (s == null || s.posts() == 0) {
            return;
        }
        section(document, upcoming ? "Pre-release buzz" : "Live social reach");
        PdfPTable totals = new PdfPTable(4);
        totals.setWidthPercentage(100);
        String[][] tiles = {
                {"Posts", count(s.posts())}, {"Views", count(s.views())},
                {"Engagement", count(s.engagement())}, {"Engagement / post", plain(s.engagementPerPost())},
                {"Likes", count(s.likes())}, {"Comments", count(s.comments())},
                {"Shares", count(s.shares())}, {"Positive posts", percent(s.positiveShare())},
        };
        for (String[] tile : tiles) {
            PdfPCell cell = new PdfPCell();
            cell.setPadding(6);
            cell.setBorderColor(ROW_ALT);
            cell.addElement(new Paragraph(tile[0], BODY_MUTED));
            cell.addElement(new Paragraph(safe(tile[1]), BODY_BOLD));
            totals.addCell(cell);
        }
        document.add(totals);

        if (!s.platforms().isEmpty()) {
            Paragraph sub = new Paragraph("By platform", SUBSECTION);
            sub.setSpacingBefore(8);
            sub.setSpacingAfter(4);
            document.add(sub);
            PdfPTable table = new PdfPTable(new float[]{2, 1, 1, 1});
            table.setWidthPercentage(100);
            header(table, "Platform", "Posts", "Views", "Engagement");
            int i = 0;
            for (PlatformReach p : s.platforms()) {
                Color bg = i++ % 2 == 1 ? ROW_ALT : null;
                table.addCell(cell(p.platform(), BODY, bg));
                table.addCell(cell(count(p.posts()), BODY, bg));
                table.addCell(cell(p.views() == 0 ? "n/a" : count(p.views()), BODY, bg));
                table.addCell(cell(count(p.engagement()), BODY, bg));
            }
            document.add(table);
        }

        if (!s.peerComparison().isEmpty() && s.trackedPeerCount() > 0) {
            Paragraph sub = new Paragraph("Against " + s.trackedPeerCount() + " other tracked movies in the same language",
                    SUBSECTION);
            sub.setSpacingBefore(8);
            sub.setSpacingAfter(2);
            document.add(sub);
            if (s.comparisonBasis() != null) {
                Paragraph basis = new Paragraph(safe(s.comparisonBasis()), BODY_MUTED);
                basis.setSpacingAfter(4);
                document.add(basis);
            }
            metricTable(document, s.peerComparison(), currency);
        }
    }

    private void addNotes(Document document, List<String> notes) throws DocumentException {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        section(document, "Methodology");
        for (String note : notes) {
            Paragraph p = new Paragraph("-  " + safe(note), BODY_MUTED);
            p.setSpacingAfter(2);
            document.add(p);
        }
    }

    // ---- building blocks -----------------------------------------------------------------------

    /** What the cohort's films earned. */
    private void addOutcomes(Document document, CohortOutcomes o, String currency) throws DocumentException {
        if (o == null || (o.filmsWithGross() == 0 && o.classified() == 0)) {
            return;
        }
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        String[][] tiles = {
                {"Median worldwide gross", money(o.medianGross(), currency)},
                {"Top 25% gross", money(o.topQuartileGross(), currency)},
                {"Median return on budget", ratio(o.medianReturnOnBudget())},
                {"Hits", o.classified() == 0 ? null
                        : o.hits() + " of " + o.classified() + (o.hitRate() == null ? "" : " (" + o.hitRate() + "%)")},
        };
        for (String[] tile : tiles) {
            PdfPCell cell = new PdfPCell();
            cell.setPadding(6);
            cell.setBorderColor(ROW_ALT);
            cell.addElement(new Paragraph(tile[0], BODY_MUTED));
            cell.addElement(new Paragraph(safe(tile[1] == null ? "n/a" : tile[1]), BODY_BOLD));
            table.addCell(cell);
        }
        document.add(table);
    }

    private static boolean isUpcoming(RightsValuationReportResponse report) {
        return RightsValuationReportService.UPCOMING.equals(report.movie().releaseStatus());
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private void metricTable(Document document, List<MetricComparison> metrics, String currency)
            throws DocumentException {
        PdfPTable table = new PdfPTable(new float[]{2.6f, 1.3f, 1.3f, 1.3f, 1.1f, 0.9f});
        table.setWidthPercentage(100);
        header(table, "Metric", "This film", "Peer median", "Top 25%", "Ahead of", "Rank");
        int i = 0;
        for (MetricComparison c : metrics) {
            Color bg = i++ % 2 == 1 ? ROW_ALT : null;
            boolean strong = c.percentile() != null && c.percentile() >= RightsValuationReportService.STRONG_PERCENTILE;
            table.addCell(cell(c.label(), BODY, bg));
            table.addCell(cell(format(c.value(), c.unit(), currency), strong ? STRONG : BODY_BOLD, bg));
            table.addCell(cell(format(c.peerMedian(), c.unit(), currency), BODY, bg));
            table.addCell(cell(format(c.peerTopQuartile(), c.unit(), currency), BODY, bg));
            table.addCell(cell(c.percentile() == null ? "n/a" : c.percentile() + "%", strong ? STRONG : BODY, bg));
            table.addCell(cell(c.rank() == null ? "n/a" : "#" + c.rank() + " / " + (c.peersWithData() + 1), BODY, bg));
        }
        document.add(table);
    }

    private static void section(Document document, String title) throws DocumentException {
        Paragraph p = new Paragraph(safe(title), SECTION);
        p.setSpacingBefore(14);
        p.setSpacingAfter(6);
        document.add(p);
    }

    private static void header(PdfPTable table, String... labels) {
        for (String label : labels) {
            PdfPCell cell = new PdfPCell(new Phrase(label, TH));
            cell.setBackgroundColor(BRAND);
            cell.setPadding(5);
            cell.setBorderColor(BRAND);
            table.addCell(cell);
        }
    }

    private static PdfPCell cell(String text, Font font, Color background) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(text == null ? "n/a" : text), font));
        cell.setPadding(5);
        cell.setBorderColor(ROW_ALT);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        return cell;
    }

    private static void addFact(List<String[]> rows, String label, String value) {
        if (value != null && !value.isBlank()) {
            rows.add(new String[]{label, value});
        }
    }

    private static String format(Double value, String unit, String currency) {
        if (value == null) {
            return "n/a";
        }
        return switch (unit) {
            case "MONEY" -> money(value, currency);
            case "RATIO" -> ratio(value);
            case "RATING" -> String.format(Locale.US, "%.1f/10", value);
            case "PERCENT" -> percent(value);
            default -> compact(value);
        };
    }

    private static String money(Double value, String currency) {
        if (value == null) {
            return null;
        }
        if ("INR".equals(currency)) {
            return Math.abs(value) >= 1e7
                    ? String.format(Locale.US, "Rs. %.2f Cr", value / 1e7)
                    : String.format(Locale.US, "Rs. %.2f L", value / 1e5);
        }
        return Math.abs(value) >= 1e6
                ? String.format(Locale.US, "$%.2fM", value / 1e6)
                : String.format(Locale.US, "$%.0fK", value / 1e3);
    }

    private static String ratio(Double value) {
        return value == null ? null : String.format(Locale.US, "%.2fx", value);
    }

    private static String percent(Double value) {
        return value == null ? "n/a" : String.format(Locale.US, "%.1f%%", value);
    }

    private static String plain(Double value) {
        return value == null ? "n/a" : String.format(Locale.US, "%.2f", value);
    }

    private static String count(Long value) {
        return value == null ? null : compact(value.doubleValue());
    }

    static String compact(double value) {
        double abs = Math.abs(value);
        if (abs >= 1e9) {
            return String.format(Locale.US, "%.2fB", value / 1e9);
        }
        if (abs >= 1e6) {
            return String.format(Locale.US, "%.2fM", value / 1e6);
        }
        if (abs >= 1e3) {
            return String.format(Locale.US, "%.1fK", value / 1e3);
        }
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.US, "%.2f", value);
    }

    /** The built-in PDF fonts can't draw the rupee sign. */
    private static String safe(String text) {
        return text == null ? "" : text.replace("₹", "Rs. ");
    }
}
