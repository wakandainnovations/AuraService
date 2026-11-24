package com.aura.service.service;

import com.aura.service.currency.ExchangeRateUnavailableException;
import com.aura.service.currency.UsdInrRate;
import com.aura.service.dto.RightsValuationReportResponse;
import com.aura.service.dto.RightsValuationReportResponse.MetricComparison;
import com.aura.service.dto.RightsValuationReportResponse.PeerCohort;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.predictor.BudgetCurrency;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.BoxOfficeFilmLookup.BoxOfficeFilm;
import com.aura.service.service.SocialReachLookup.PlatformTotals;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link RightsValuationReportService}: cohort selection (language, budget band, release
 * window, self-exclusion, the lead star's earlier films in any language), percentile/rank maths, INR
 * display conversion with ranking left in USD, the social-reach comparison against tracked movies in
 * the same language (duplicate tracked copies of one film counted once), and that the report never
 * carries a unique-user count. Lookups are lambdas - see mockito-no-concrete-class-mocks.
 */
class RightsValuationReportServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);
    private static final UsdInrRate RATE = new UsdInrRate(LocalDate.of(2026, 11, 23), 0.0125); // ₹80 per $

    private final EntityAccessService access = mock(EntityAccessService.class);
    private final ManagedEntityRepository entities = mock(ManagedEntityRepository.class);
    private final List<BoxOfficeFilm> films = new ArrayList<>();
    private final List<PlatformTotals> social = new ArrayList<>();
    /** What the lookup returns when asked for posts up to N days before release. */
    private final List<PlatformTotals> socialSameStage = new ArrayList<>();
    private final List<Integer> stageOffsetsRequested = new ArrayList<>();
    private boolean rateAvailable = true;

    private RightsValuationReportService service;
    private ManagedEntity movie;

    @BeforeEach
    void setUp() {
        service = new RightsValuationReportService(access, entities, () -> films,
                (ids, days) -> {
                    if (days != null) {
                        stageOffsetsRequested.add(days);
                    }
                    return (days == null ? social : socialSameStage).stream()
                            .filter(t -> ids.contains(t.entityId())).toList();
                },
                () -> {
                    if (!rateAvailable) {
                        throw new ExchangeRateUnavailableException("down");
                    }
                    return RATE;
                }, CLOCK);

        movie = entity(1L, "Big Hit", "Tamil");
        movie.setReleaseDate(LocalDate.of(2026, 5, 1));
        movie.setGenre("Action,Drama");
        movie.setBudget(10_000_000.0);
        when(access.assertOwnedByCurrentUser(1L)).thenReturn(movie);
        when(entities.findByTypeAndLanguageIgnoreCase(eq("MOVIE"), eq("Tamil"))).thenReturn(List.of(movie));

        // The movie's own dataset row, released a couple of weeks off the tracked date.
        films.add(film("Big Hit", LocalDate.of(2026, 5, 14), "tamil", List.of("tamil", "telugu"), "Action, Drama",
                7.5, 10_000_000.0, 40_000_000.0, 20_000_000L, "Star A"));
        // Similar-budget Tamil peers, grossing 10M..50M.
        films.add(film("Peer 1", LocalDate.of(2024, 1, 1), "tamil", null, "Action", 6.0, 9_000_000.0, 10_000_000.0, 1_000_000L, "Star B"));
        films.add(film("Peer 2", LocalDate.of(2024, 2, 1), "tamil", null, "Drama", 7.4, 11_000_000.0, 20_000_000.0, 2_000_000L, "Star C"));
        films.add(film("Peer 3", LocalDate.of(2024, 3, 1), "tamil", null, "Comedy", 7.6, 12_000_000.0, 30_000_000.0, 3_000_000L, "Star D"));
        films.add(film("Peer 4", LocalDate.of(2025, 3, 1), "tamil", null, "Action", 8.0, 8_000_000.0, 50_000_000.0, 4_000_000L, "Star E"));
        // Excluded from language cohorts: other language, too old, not yet released, budget out of band.
        films.add(film("Hindi Film", LocalDate.of(2025, 1, 1), "hindi", null, "Action", 7.5, 10_000_000.0, 99_000_000.0, null, "Star F"));
        films.add(film("Old Film", LocalDate.of(2010, 1, 1), "tamil", null, "Action", 7.5, 10_000_000.0, 99_000_000.0, null, "Star G"));
        films.add(film("Future Film", LocalDate.of(2027, 1, 1), "tamil", null, "Action", 7.5, 10_000_000.0, 99_000_000.0, null, "Star H"));
        films.add(film("Tiny Film", LocalDate.of(2025, 1, 1), "tamil", null, "Action", 7.5, 1_000_000.0, 99_000_000.0, null, "Star I"));
        // Star A's earlier films, one of them in Telugu - and an old one, which the star cohort still counts.
        films.add(verdict(film("Star A Telugu", LocalDate.of(2023, 6, 1), "telugu", null, "Action", 6.5, 5_000_000.0, 15_000_000.0, null, "Star A"), "hit"));
        films.add(verdict(film("Star A Debut", LocalDate.of(2012, 6, 1), "tamil", null, "Drama", 6.0, 1_000_000.0, 2_000_000.0, null, "Star A"), "flop"));
        films.add(verdict(film("Star A Debut 2", LocalDate.of(2013, 6, 1), "tamil", null, "Drama", 6.1, 1_000_000.0, 3_000_000.0, null, "Star A"), "hit"));
    }

    @Test
    void ranksAgainstSimilarBudgetFilms_inInr() {
        RightsValuationReportResponse report = service.buildReport(1L, BudgetCurrency.INR, 8);

        PeerCohort budget = cohort(report, "SIMILAR_BUDGET");
        assertThat(budget.closestPeers()).extracting(c -> c.title())
                .containsExactlyInAnyOrder("Peer 1", "Peer 2", "Peer 3", "Peer 4");
        MetricComparison gross = metric(budget, "worldwideGross");
        // 40M beats 10M, 20M, 30M out of 4 peers; ranked #2 behind 50M.
        assertThat(gross.percentile()).isEqualTo(75);
        assertThat(gross.rank()).isEqualTo(2);
        assertThat(gross.peersWithData()).isEqualTo(4);
        // Displayed in rupees at ₹80/$: 40M USD -> 3.2B INR; median of 10/20/30/50M = 25M -> 2B INR.
        assertThat(gross.value()).isEqualTo(3_200_000_000.0);
        assertThat(gross.peerMedian()).isEqualTo(2_000_000_000.0);
        assertThat(gross.multipleOfMedian()).isEqualTo(1.6);

        assertThat(report.currency()).isEqualTo("INR");
        assertThat(report.usdToInrRate()).isEqualTo(80.0);
        assertThat(report.movie().worldwideGross()).isEqualTo(3_200_000_000.0);
        assertThat(report.movie().releaseLanguages()).containsExactly("Tamil", "Telugu");
        assertThat(report.sellingPoints())
                // Per metric, the strongest cohort wins: gross is #1 among the star's films (beats #2 on budget).
                .anyMatch(p -> p.startsWith("Worldwide gross of ₹320.00 Cr ranks #1 against Star A's 3 previous films"))
                .anyMatch(p -> p.startsWith("Released in 2 languages"));
    }

    @Test
    void similarRatingCohortUsesRatingBandAndDropsRatingMetric() {
        RightsValuationReportResponse report = service.buildReport(1L, BudgetCurrency.USD, 8);

        PeerCohort rating = cohort(report, "SIMILAR_RATING");
        // 7.5 ± 0.5 among Tamil films in the window: Peer 2 (7.4), Peer 3 (7.6), Peer 4 (8.0), Tiny Film (7.5).
        assertThat(rating.closestPeers()).extracting(c -> c.title())
                .containsExactlyInAnyOrder("Peer 2", "Peer 3", "Peer 4", "Tiny Film");
        assertThat(rating.metrics()).extracting(MetricComparison::metric).doesNotContain("audienceRating");
        assertThat(report.usdToInrRate()).isNull();
        assertThat(metric(cohort(report, "SIMILAR_BUDGET"), "worldwideGross").value()).isEqualTo(40_000_000.0);
    }

    @Test
    void leadActorCohortSpansLanguagesAndDates() {
        RightsValuationReportResponse report = service.buildReport(1L, BudgetCurrency.USD, 8);
        PeerCohort lead = cohort(report, "SAME_LEAD_ACTOR");

        assertThat(lead.closestPeers()).extracting(c -> c.title())
                .containsExactly("Star A Telugu", "Star A Debut 2", "Star A Debut");
        MetricComparison gross = metric(lead, "worldwideGross");
        assertThat(gross.percentile()).isEqualTo(100);
        assertThat(gross.rank()).isEqualTo(1);
        assertThat(lead.closestPeers()).allMatch(c -> Boolean.TRUE.equals(c.outgrossedByThisMovie()));
        assertThat(lead.outcomes().hits()).isEqualTo(2);
        assertThat(lead.outcomes().classified()).isEqualTo(3);
        assertThat(lead.outcomes().hitRate()).isEqualTo(67);
        // Released movies are pitched on their own results, not the star's record.
        assertThat(report.movie().releaseStatus()).isEqualTo("RELEASED");
        assertThat(report.sellingPoints()).noneMatch(p -> p.contains("were hits"));
    }

    @Test
    void upcomingMovie_pitchesTrackRecordAndDropsPostReleaseMetrics() {
        films.remove(0); // not in the box-office dataset yet
        movie.setReleaseDate(LocalDate.of(2027, 3, 1));
        movie.setActors(new ArrayList<>(List.of("Star A")));

        RightsValuationReportResponse report = service.buildReport(1L, BudgetCurrency.USD, 8);

        assertThat(report.movie().releaseStatus()).isEqualTo("UPCOMING");
        assertThat(report.movie().daysToRelease()).isEqualTo(97);
        assertThat(report.peerCohorts()).extracting(PeerCohort::kind)
                .contains("SIMILAR_BUDGET", "SIMILAR_GENRE_AND_SCALE", "SAME_LEAD_ACTOR")
                .doesNotContain("SIMILAR_RATING");
        // Nothing to rank yet: no gross/rating/screens rows, only what peers earned.
        assertThat(report.peerCohorts()).allMatch(c -> c.metrics().isEmpty());
        PeerCohort budget = cohort(report, "SIMILAR_BUDGET");
        assertThat(budget.outcomes().filmsWithGross()).isEqualTo(4);
        assertThat(budget.outcomes().medianGross()).isEqualTo(25_000_000.0);
        assertThat(report.sellingPoints().get(0))
                .isEqualTo("Star A's 3 previous films: 2 of 3 were hits, with a median worldwide gross of $3.00M.");
    }

    @Test
    void upcomingMovie_ranksVideoViewsSoFarAgainstPeersLifetime() {
        films.set(0, film("Big Hit", LocalDate.of(2027, 3, 1), "tamil", null, "Action, Drama",
                null, 10_000_000.0, null, 3_500_000L, "Star A"));
        movie.setReleaseDate(LocalDate.of(2027, 3, 1));

        PeerCohort budget = cohort(service.buildReport(1L, BudgetCurrency.USD, 8), "SIMILAR_BUDGET");

        assertThat(budget.metrics()).extracting(MetricComparison::metric)
                .containsExactlyInAnyOrder("totalVideoViews", "trailerViews");
        MetricComparison trailer = metric(budget, "trailerViews");
        assertThat(trailer.percentile()).isEqualTo(75);
        assertThat(trailer.rank()).isEqualTo(2);
    }

    @Test
    void upcomingMovie_comparesBuzzAtTheSameStageBeforeRelease() {
        movie.setReleaseDate(LocalDate.of(2027, 3, 1)); // 97 days away
        films.remove(0);
        ManagedEntity released = entity(2L, "Released Peer", "Tamil");
        released.setReleaseDate(LocalDate.of(2026, 6, 1));
        ManagedEntity alsoReleased = entity(3L, "Another Peer", "Tamil");
        alsoReleased.setReleaseDate(LocalDate.of(2026, 9, 1));
        ManagedEntity lastPeer = entity(4L, "Last Peer", "Tamil");
        lastPeer.setReleaseDate(LocalDate.of(2026, 10, 1));
        ManagedEntity tooFarOut = entity(5L, "Far Out", "Tamil"); // its 97-days-before point is still ahead
        tooFarOut.setReleaseDate(LocalDate.of(2027, 12, 1));
        when(entities.findByTypeAndLanguageIgnoreCase(eq("MOVIE"), eq("Tamil")))
                .thenReturn(List.of(movie, released, alsoReleased, lastPeer, tooFarOut));
        social.add(new PlatformTotals(1L, "X", 300, 200, 10, 50_000, 3_000, 100, 0));
        social.add(new PlatformTotals(2L, "X", 9_999, 0, 0, 0, 0, 0, 0)); // all-time: must not be used
        socialSameStage.add(new PlatformTotals(2L, "X", 100, 10, 10, 1_000, 10, 1, 0));
        socialSameStage.add(new PlatformTotals(3L, "X", 200, 20, 20, 2_000, 20, 2, 0));
        socialSameStage.add(new PlatformTotals(4L, "X", 400, 40, 40, 4_000, 40, 4, 0));
        socialSameStage.add(new PlatformTotals(5L, "X", 5_000, 0, 0, 0, 0, 0, 0));

        RightsValuationReportResponse.SocialReach reach = service.buildReport(1L, BudgetCurrency.USD, 8).socialReach();

        assertThat(stageOffsetsRequested).containsExactly(97);
        assertThat(reach.posts()).isEqualTo(300);
        assertThat(reach.trackedPeerCount()).isEqualTo(3);
        assertThat(reach.comparisonBasis()).contains("up to 97 days before their own release");
        MetricComparison posts = reach.peerComparison().stream()
                .filter(c -> c.metric().equals("posts")).findFirst().orElseThrow();
        assertThat(posts.label()).isEqualTo("Pre-release post volume");
        assertThat(posts.percentile()).isEqualTo(67);
    }

    @Test
    void socialReachRanksAgainstTrackedMoviesInLanguage_countingDuplicatesOnce() {
        ManagedEntity peerA = entity(2L, "Other: Film", "Tamil");
        ManagedEntity peerACopy = entity(3L, "Other Film", "Tamil");
        ManagedEntity peerB = entity(4L, "Third", "Tamil");
        ManagedEntity peerC = entity(5L, "Fourth", "Tamil");
        when(entities.findByTypeAndLanguageIgnoreCase(eq("MOVIE"), eq("Tamil")))
                .thenReturn(List.of(movie, peerA, peerACopy, peerB, peerC));
        social.add(new PlatformTotals(1L, "X", 900, 600, 100, 500_000, 9_000, 800, 200));
        social.add(new PlatformTotals(1L, "YOUTUBE", 100, 50, 10, 0, 1_000, 0, 0));
        social.add(new PlatformTotals(2L, "X", 100, 10, 10, 10_000, 100, 10, 0));
        social.add(new PlatformTotals(3L, "X", 300, 30, 30, 20_000, 300, 30, 0));
        social.add(new PlatformTotals(4L, "X", 400, 40, 40, 30_000, 400, 40, 0));
        social.add(new PlatformTotals(5L, "X", 2_000, 200, 200, 900_000, 20_000, 400, 0));

        RightsValuationReportResponse.SocialReach reach = service.buildReport(1L, BudgetCurrency.USD, 8).socialReach();

        assertThat(reach.posts()).isEqualTo(1_000);
        assertThat(reach.views()).isEqualTo(500_000);
        assertThat(reach.engagement()).isEqualTo(11_000);
        assertThat(reach.engagementPerPost()).isEqualTo(11.0);
        assertThat(reach.positiveShare()).isEqualTo(65.0);
        assertThat(reach.netSentiment()).isEqualTo(54.0);
        assertThat(reach.platforms()).extracting(p -> p.platform()).containsExactly("X", "YOUTUBE");
        // Entities 2 and 3 are the same film; only its busier copy (300 posts) counts.
        assertThat(reach.trackedPeerCount()).isEqualTo(3);
        MetricComparison posts = reach.peerComparison().stream()
                .filter(c -> c.metric().equals("posts")).findFirst().orElseThrow();
        assertThat(posts.percentile()).isEqualTo(67);
        assertThat(posts.rank()).isEqualTo(2);
    }

    @Test
    void neverReportsUniqueUsers() throws Exception {
        social.add(new PlatformTotals(1L, "X", 10, 5, 1, 100, 10, 1, 0));
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(service.buildReport(1L, BudgetCurrency.INR, 8));

        String lower = json.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("uniqueusers").doesNotContain("unique_users")
                .doesNotContain("\"authors\"").doesNotContain("uniqueauthors");
    }

    @Test
    void fallsBackToUsdWhenNoExchangeRate() {
        rateAvailable = false;

        RightsValuationReportResponse report = service.buildReport(1L, BudgetCurrency.INR, 8);

        assertThat(report.currency()).isEqualTo("USD");
        assertThat(report.movie().worldwideGross()).isEqualTo(40_000_000.0);
        assertThat(report.methodologyNotes()).anyMatch(n -> n.contains("No USD/INR exchange rate"));
    }

    @Test
    void unmatchedMovieUsesTrackedBudgetAndSkipsRatingCohort() {
        films.remove(0);

        RightsValuationReportResponse report = service.buildReport(1L, BudgetCurrency.USD, 8);

        assertThat(report.movie().boxOfficeDataAvailable()).isFalse();
        assertThat(report.peerCohorts()).extracting(PeerCohort::kind)
                .contains("SIMILAR_BUDGET", "SIMILAR_GENRE_AND_SCALE")
                .doesNotContain("SIMILAR_RATING");
        assertThat(metric(cohort(report, "SIMILAR_BUDGET"), "worldwideGross").percentile()).isNull();
    }

    @Test
    void rejectsNonMovies() {
        ManagedEntity person = entity(9L, "Someone", "Tamil");
        person.setType("PERSON");
        when(access.assertOwnedByCurrentUser(9L)).thenReturn(person);

        assertThatThrownBy(() -> service.buildReport(9L, BudgetCurrency.INR, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ManagedEntity entity(Long id, String name, String language) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName(name);
        e.setType("MOVIE");
        e.setLanguage(language);
        return e;
    }

    private static BoxOfficeFilm film(String title, LocalDate date, String language, List<String> languages,
                                      String genre, Double rating, Double budget, Double revenue, Long trailerViews,
                                      String lead) {
        return new BoxOfficeFilm(title, date, language, languages == null ? List.of(language) : languages, genre,
                rating, budget, revenue, trailerViews, null, null, null, null, null, null, null, null, lead, null, null,
                null);
    }

    private static BoxOfficeFilm verdict(BoxOfficeFilm f, String verdict) {
        return new BoxOfficeFilm(f.title(), f.releaseDate(), f.originalLanguage(), f.languages(), f.genre(), f.rating(),
                f.budget(), f.revenue(), f.trailerViews(), f.teaserViews(), f.songViews(), f.trailerComments(),
                f.trailerViewsWeekBefore(), f.screens(), f.runtime(), f.cbfcRating(), f.productionCompanies(),
                f.leadActor(), f.director(), f.musicDirector(), verdict);
    }

    private static PeerCohort cohort(RightsValuationReportResponse report, String kind) {
        return report.peerCohorts().stream().filter(c -> c.kind().equals(kind)).findFirst().orElseThrow();
    }

    private static MetricComparison metric(PeerCohort cohort, String key) {
        return cohort.metrics().stream().filter(m -> m.metric().equals(key)).findFirst().orElseThrow();
    }
}
