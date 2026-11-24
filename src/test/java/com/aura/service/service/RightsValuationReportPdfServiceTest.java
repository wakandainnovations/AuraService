package com.aura.service.service;

import com.aura.service.dto.RightsValuationReportResponse;
import com.aura.service.dto.RightsValuationReportResponse.CohortOutcomes;
import com.aura.service.dto.RightsValuationReportResponse.ComparableFilm;
import com.aura.service.dto.RightsValuationReportResponse.MetricComparison;
import com.aura.service.dto.RightsValuationReportResponse.MovieProfile;
import com.aura.service.dto.RightsValuationReportResponse.PeerCohort;
import com.aura.service.dto.RightsValuationReportResponse.PlatformReach;
import com.aura.service.dto.RightsValuationReportResponse.SocialReach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Renders full and sparse reports through the real OpenPDF path and checks the download name. */
class RightsValuationReportPdfServiceTest {

    private final RightsValuationReportPdfService service = new RightsValuationReportPdfService();

    @Test
    void rendersFullReport() {
        assertWellFormed(service.render(report(true)));
    }

    @Test
    void rendersSparseReport() {
        assertWellFormed(service.render(report(false)));
    }

    @Test
    void rendersUpcomingReportWithCohorts() {
        RightsValuationReportResponse full = report(true);
        MovieProfile m = full.movie();
        MovieProfile upcoming = new MovieProfile(m.entityId(), m.name(), m.language(), m.industry(), m.genre(),
                LocalDate.of(2027, 6, 18), "UPCOMING", 206, m.runtimeMinutes(), null, m.releaseLanguages(), null,
                null, m.leadActor(), m.director(), null, m.budget(), null, null, null, null, null, null, null, null,
                null, false);
        assertWellFormed(service.render(new RightsValuationReportResponse(upcoming, full.currency(),
                full.usdToInrRate(), full.exchangeRateDate(), full.sellingPoints(), List.of(), full.peerCohorts(),
                full.socialReach(), full.methodologyNotes(), full.generatedAt())));
    }

    @Test
    void slugifiesFileName() {
        assertThat(service.fileName(report(false))).isEqualTo("balan-the-boy-rights-valuation-report.pdf");
    }

    private static void assertWellFormed(byte[] pdf) {
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(new String(pdf, pdf.length - 7, 7, StandardCharsets.ISO_8859_1)).contains("%%EOF");
    }

    private static RightsValuationReportResponse report(boolean full) {
        MovieProfile movie = new MovieProfile(1L, "Balan: The Boy", "Kannada", "Sandalwood", "Drama, Thriller",
                LocalDate.of(2026, 6, 19), full ? "RELEASED" : "UPCOMING", full ? null : 40, full ? 142 : null, full ? "UA" : null,
                full ? List.of("Kannada", "Malayalam") : List.of(), full ? 400 : null, null, "Lead", "Director",
                null, full ? 1.6e8 : null, full ? 4.2e8 : null, full ? 2.63 : null, full ? 7.8 : null,
                full ? 3_000_000L : null, null, null, full ? 3_000_000L : null, full ? 4_000L : null, null, full);
        if (!full) {
            return new RightsValuationReportResponse(movie, "USD", null, null, List.of(), List.of(), List.of(),
                    new SocialReach(0, 0, 0, 0, 0, 0, null, null, null, List.of(), 0, null, List.of()), List.of(),
                    Instant.parse("2026-11-24T00:00:00Z"));
        }
        List<MetricComparison> metrics = List.of(
                new MetricComparison("worldwideGross", "Worldwide gross", "MONEY", 4.2e8, 2.1e8, 3.0e8, 85, 2, 12, 2.0),
                new MetricComparison("audienceRating", "Audience rating", "RATING", 7.8, 6.4, 7.0, 90, 1, 12, 1.22),
                new MetricComparison("screens", "Release screens", "COUNT", null, 300.0, 500.0, null, null, 2, null));
        PeerCohort cohort = new PeerCohort("SIMILAR_BUDGET", "Similar-budget Kannada films", "criteria", 12,
                new CohortOutcomes(12, 2.1e8, 3.0e8, 1.4, 7, 12, 58), metrics,
                List.of(new ComparableFilm("Peer", LocalDate.of(2025, 1, 1), "Kannada", 1.5e8, 2.0e8, 1.33, 6.5,
                        1_000_000L, "hit", true)));
        SocialReach social = new SocialReach(1180, 250_000, 9_000, 400, 20, 9_420, 7.98, 62.5, 40.0,
                List.of(new PlatformReach("X", 1000, 250_000, 9_000), new PlatformReach("YOUTUBE", 180, 0, 420)),
                5, "All tracked posts for each movie", List.of(new MetricComparison("posts", "Social post volume", "COUNT", 1180.0, 600.0, 900.0, 80, 2, 5, 1.97)));
        return new RightsValuationReportResponse(movie, "INR", 84.2, LocalDate.of(2026, 11, 23),
                List.of("Worldwide gross of ₹42.00 Cr ranks #2 against 12 similar-budget Kannada films."),
                List.of("internal"), List.of(cohort), social, List.of("note"), Instant.parse("2026-11-24T00:00:00Z"));
    }
}
