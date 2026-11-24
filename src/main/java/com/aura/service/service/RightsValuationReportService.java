package com.aura.service.service;

import com.aura.service.currency.ExchangeRateUnavailableException;
import com.aura.service.currency.UsdInrRate;
import com.aura.service.currency.UsdInrRateProvider;
import com.aura.service.dto.RightsValuationReportResponse;
import com.aura.service.dto.RightsValuationReportResponse.CohortOutcomes;
import com.aura.service.dto.RightsValuationReportResponse.ComparableFilm;
import com.aura.service.dto.RightsValuationReportResponse.MetricComparison;
import com.aura.service.dto.RightsValuationReportResponse.MovieProfile;
import com.aura.service.dto.RightsValuationReportResponse.PeerCohort;
import com.aura.service.dto.RightsValuationReportResponse.PlatformReach;
import com.aura.service.dto.RightsValuationReportResponse.SocialReach;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.EntityType;
import com.aura.service.predictor.BudgetCurrency;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.BoxOfficeFilmLookup.BoxOfficeFilm;
import com.aura.service.service.SocialReachLookup.PlatformTotals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the {@link RightsValuationReportResponse} for a movie either side of its release.
 *
 * <p><b>Released</b> - the movie's own gross, return on budget, rating and video reach are ranked
 * against comparable films from {@code india_movies_data_collection}, and its live social reach against
 * other tracked movies in its language.
 *
 * <p><b>Upcoming</b> - nothing post-release exists yet, so the report rests on what can be shown
 * today: what comparable films and the cast and crew's previous films earned (median gross, hit rate),
 * the movie's video views so far ranked against peers' lifetime views (conservative, since peers' totals
 * include post-release viewing), and its social buzz ranked against tracked movies <em>at the same
 * stage</em> - only their posts up to the same number of days before their own release.
 *
 * <p>Cohorts (released films only, excluding the movie itself):
 * <ul>
 *   <li>{@code SIMILAR_BUDGET} - same original language, budget 0.6x-1.6x, within the lookback window;</li>
 *   <li>{@code SIMILAR_RATING} - released movies only: same language, rating within ±0.5;</li>
 *   <li>{@code SIMILAR_GENRE_AND_SCALE} - same language, a shared genre and (when the budget is known)
 *       budget 1/3x-3x, within the window;</li>
 *   <li>{@code SAME_LEAD_ACTOR} / {@code SAME_DIRECTOR} / {@code SAME_MUSIC_DIRECTOR} - that person's
 *       last {@value #TALENT_FILMS} films before this one, any language, any date.</li>
 * </ul>
 * Ranking uses the stored USD values; only displayed money is converted. Nothing here counts unique
 * users or authors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RightsValuationReportService {

    public static final String RELEASED = "RELEASED";
    public static final String UPCOMING = "UPCOMING";

    static final int MIN_PEERS_TO_RANK = 3;
    static final int TALENT_FILMS = 8;
    static final int CLOSEST_PEERS = 6;
    static final int MAX_POINTS = 8;
    static final int STRONG_PERCENTILE = 60;
    static final int WEAK_PERCENTILE = 40;
    /** How far apart the tracked release date and the dataset's may be for the same film. */
    static final long MATCH_WINDOW_DAYS = 60;

    private static final Set<String> TALENT_KINDS = Set.of("SAME_LEAD_ACTOR", "SAME_DIRECTOR", "SAME_MUSIC_DIRECTOR");

    private final EntityAccessService entityAccessService;
    private final ManagedEntityRepository managedEntityRepository;
    private final BoxOfficeFilmLookup boxOfficeFilmLookup;
    private final SocialReachLookup socialReachLookup;
    private final UsdInrRateProvider usdInrRateProvider;
    private final Clock clock;

    enum Unit { MONEY, RATIO, RATING, COUNT, PERCENT }

    private record Metric(String key, String label, Unit unit, Function<BoxOfficeFilm, Double> value) {
    }

    private static final Metric GROSS = new Metric("worldwideGross", "Worldwide gross", Unit.MONEY,
            BoxOfficeFilm::revenue);
    private static final Metric ROI = new Metric("returnOnBudget", "Return on budget", Unit.RATIO,
            BoxOfficeFilm::returnOnBudget);
    private static final Metric RATING = new Metric("audienceRating", "Audience rating", Unit.RATING,
            BoxOfficeFilm::rating);
    private static final Metric VIDEO_VIEWS = new Metric("totalVideoViews", "Trailer, teaser & song views",
            Unit.COUNT, f -> toDouble(f.totalVideoViews()));
    private static final Metric TRAILER_VIEWS = new Metric("trailerViews", "Trailer views", Unit.COUNT,
            f -> toDouble(f.trailerViews()));
    private static final Metric TEASER_VIEWS = new Metric("teaserViews", "Teaser views", Unit.COUNT,
            f -> toDouble(f.teaserViews()));
    private static final Metric SONG_VIEWS = new Metric("songViews", "First-song views", Unit.COUNT,
            f -> toDouble(f.songViews()));
    private static final Metric TRAILER_WEEK_BEFORE = new Metric("trailerViewsWeekBeforeRelease",
            "Trailer views in the week before release", Unit.COUNT, f -> toDouble(f.trailerViewsWeekBefore()));
    private static final Metric TRAILER_COMMENTS = new Metric("trailerComments", "Trailer comments", Unit.COUNT,
            f -> toDouble(f.trailerComments()));
    private static final Metric SCREENS = new Metric("screens", "Release screens", Unit.COUNT,
            f -> f.screens() == null ? null : f.screens().doubleValue());

    private static final List<Metric> RELEASED_METRICS = List.of(GROSS, ROI, RATING, VIDEO_VIEWS, TRAILER_VIEWS,
            TEASER_VIEWS, SONG_VIEWS, TRAILER_WEEK_BEFORE, TRAILER_COMMENTS, SCREENS);
    /** All the movie can have before release. Peers' figures are lifetime totals. */
    private static final List<Metric> UPCOMING_METRICS =
            List.of(VIDEO_VIEWS, TRAILER_VIEWS, TEASER_VIEWS, SONG_VIEWS, TRAILER_COMMENTS);

    /** Money and count formatting for one report. Ranking never goes through this. */
    record Money(BudgetCurrency currency, UsdInrRate rate) {

        Double convert(Double usd) {
            if (usd == null) {
                return null;
            }
            return currency == BudgetCurrency.INR ? rate.usdToInr(usd) : usd;
        }

        String format(Double value, Unit unit) {
            if (value == null) {
                return "n/a";
            }
            return switch (unit) {
                case MONEY -> currency == BudgetCurrency.INR ? rupees(value) : dollars(value);
                case RATIO -> String.format(Locale.US, "%.2fx", value);
                case RATING -> String.format(Locale.US, "%.1f/10", value);
                case PERCENT -> String.format(Locale.US, "%.1f%%", value);
                case COUNT -> RightsValuationReportPdfService.compact(value);
            };
        }

        private static String rupees(double value) {
            if (Math.abs(value) >= 1e7) {
                return String.format(Locale.US, "₹%.2f Cr", value / 1e7);
            }
            return String.format(Locale.US, "₹%.2f L", value / 1e5);
        }

        private static String dollars(double value) {
            if (Math.abs(value) >= 1e6) {
                return String.format(Locale.US, "$%.2fM", value / 1e6);
            }
            return String.format(Locale.US, "$%.0fK", value / 1e3);
        }
    }

    /** A comparison worth calling out. {@code score} orders the points (higher = stronger). */
    private record Point(String key, int score, int peers, String text) {
    }

    /** Everything one report run collects while it builds the sections. */
    private record Context(BoxOfficeFilm subject, boolean upcoming, LocalDate today, Money money,
                           List<Point> strengths, List<Point> weaknesses, List<Point> outcomeStrengths,
                           List<Point> outcomeWeaknesses, List<String> notes) {
    }

    @Transactional(readOnly = true)
    public RightsValuationReportResponse buildReport(Long entityId, BudgetCurrency currency, int lookbackYears) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(entityId);
        if (!EntityType.MOVIE.name().equalsIgnoreCase(entity.getType())) {
            throw new IllegalArgumentException("Rights-valuation reports are only available for movies");
        }
        LocalDate today = LocalDate.now(clock);
        List<String> notes = new ArrayList<>();

        Money money = resolveMoney(currency, notes);
        List<BoxOfficeFilm> films = boxOfficeFilmLookup.datedFilms();
        BoxOfficeFilm datasetRow = findDatasetRow(entity, films);
        BoxOfficeFilm subject = subject(entity, datasetRow);
        boolean upcoming = isUpcoming(subject, today);
        Integer daysToRelease = upcoming && subject.releaseDate() != null
                ? (int) ChronoUnit.DAYS.between(today, subject.releaseDate()) : null;

        Context ctx = new Context(subject, upcoming, today, money, new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), notes);
        List<PeerCohort> cohorts = buildCohorts(ctx, films, lookbackYears);
        SocialReach social = buildSocialReach(ctx, entity, daysToRelease);

        List<String> sellingPoints = new ArrayList<>();
        List<String> watchouts = new ArrayList<>();
        if (upcoming) {
            // Before release the strongest evidence is the track record; lead with it.
            sellingPoints.addAll(ctx.outcomeStrengths().stream().map(Point::text).toList());
            watchouts.addAll(ctx.outcomeWeaknesses().stream().map(Point::text).toList());
        }
        sellingPoints.addAll(topPoints(ctx.strengths(), true));
        watchouts.addAll(topPoints(ctx.weaknesses(), false));
        if (subject.languages().size() > 1) {
            sellingPoints.add((upcoming ? "Releasing in " : "Released in ") + subject.languages().size()
                    + " languages (" + subject.languages().stream().map(RightsValuationReportService::titleCase)
                    .collect(Collectors.joining(", ")) + ").");
        }

        addNotes(notes, upcoming, daysToRelease, datasetRow != null, lookbackYears);
        return new RightsValuationReportResponse(
                profile(entity, subject, datasetRow != null, upcoming, daysToRelease, money),
                money.currency().name(),
                money.currency() == BudgetCurrency.INR ? money.rate().usdToInrRate() : null,
                money.currency() == BudgetCurrency.INR ? money.rate().rateDate() : null,
                sellingPoints.stream().limit(MAX_POINTS + 1).toList(),
                watchouts.stream().limit(MAX_POINTS).toList(),
                cohorts, social, notes, clock.instant());
    }

    /** Upcoming when the release date is still ahead; with no date, when no gross has been recorded. */
    static boolean isUpcoming(BoxOfficeFilm subject, LocalDate today) {
        if (subject.releaseDate() != null) {
            return subject.releaseDate().isAfter(today);
        }
        return subject.revenue() == null;
    }

    private static void addNotes(List<String> notes, boolean upcoming, Integer daysToRelease, boolean matched,
                                 int lookbackYears) {
        if (upcoming) {
            notes.add("This movie has not released yet" + (daysToRelease == null ? "" : " (" + daysToRelease
                    + " days to go)") + ". Gross, rating and screen comparisons are replaced by what comparable "
                    + "films and the cast and crew's previous films earned.");
            notes.add("Peers' video views are lifetime totals that include viewing after their release, so ranking "
                    + "this movie's views so far against them understates it.");
        } else if (!matched) {
            notes.add("This movie was not found in the box-office dataset, so gross, rating and trailer metrics "
                    + "are unavailable; cohorts use the tracked budget and genre only.");
        }
        notes.add("Comparable films come from AuraService's Indian box-office dataset (released films only). "
                + "Language cohorts use the film's original language and films released in the last "
                + lookbackYears + " years. Hits are the dataset's own hit / average / flop classification.");
        notes.add("Percentile = share of peers this movie is ahead of (ties count half); rankings need at least "
                + MIN_PEERS_TO_RANK + " peers with data.");
        notes.add("Social reach counts tracked posts, views, likes, comments, shares and sentiment. Counts of "
                + "unique users are intentionally excluded. Views are available for X and Instagram only.");
        notes.add("Tracked-movie social comparisons depend on when tracking started for each movie; peer movies "
                + "are not named.");
    }

    private Money resolveMoney(BudgetCurrency currency, List<String> notes) {
        if (currency != BudgetCurrency.INR) {
            notes.add("Money is shown in USD, as stored in the box-office dataset.");
            return new Money(BudgetCurrency.USD, null);
        }
        try {
            UsdInrRate rate = usdInrRateProvider.currentRate();
            notes.add(String.format(Locale.US, "Money is stored in USD and shown in INR at ₹%.2f per dollar (%s).",
                    rate.usdToInrRate(), rate.rateDate()));
            return new Money(BudgetCurrency.INR, rate);
        } catch (ExchangeRateUnavailableException ex) {
            log.warn("No USD/INR rate for the rights-valuation report, falling back to USD: {}", ex.getMessage());
            notes.add("No USD/INR exchange rate was available, so money is shown in USD.");
            return new Money(BudgetCurrency.USD, null);
        }
    }

    // ---- matching ------------------------------------------------------------------------------

    /** The dataset row with the same title released closest to the tracked date (within the window). */
    static BoxOfficeFilm findDatasetRow(ManagedEntity entity, List<BoxOfficeFilm> films) {
        String key = titleKey(entity.getName());
        LocalDate release = entity.getReleaseDate();
        return films.stream()
                .filter(f -> titleKey(f.title()).equals(key))
                .filter(f -> release == null || daysApart(f.releaseDate(), release) <= MATCH_WINDOW_DAYS)
                .min(Comparator.comparingLong(f -> release == null
                        ? -f.releaseDate().toEpochDay()
                        : daysApart(f.releaseDate(), release)))
                .orElse(null);
    }

    /** The dataset row with tracked-entity facts filling its gaps (or standing alone without a row). */
    private static BoxOfficeFilm subject(ManagedEntity entity, BoxOfficeFilm row) {
        String language = entity.getLanguage() != null
                ? entity.getLanguage().trim().toLowerCase(Locale.ROOT)
                : row != null ? row.originalLanguage() : null;
        List<String> languages = row != null && !row.languages().isEmpty()
                ? row.languages()
                : language == null ? List.of() : List.of(language);
        Double budget = row != null && row.budget() != null ? row.budget() : positive(entity.getBudget());
        String lead = row != null && row.leadActor() != null ? row.leadActor()
                : entity.getActors() == null || entity.getActors().isEmpty() ? null : entity.getActors().get(0);
        String genre = row != null && row.genre() != null ? row.genre() : entity.getGenre();
        LocalDate release = entity.getReleaseDate() != null ? entity.getReleaseDate()
                : row != null ? row.releaseDate() : null;
        if (row == null) {
            return new BoxOfficeFilm(entity.getName(), release, language, languages, genre, null, budget, null,
                    null, null, null, null, null, null, entity.getRuntime(), null, entity.getProductionCompany(),
                    lead, entity.getDirector(), null, null);
        }
        return new BoxOfficeFilm(entity.getName(), release, language, languages, genre, row.rating(), budget,
                row.revenue(), row.trailerViews(), row.teaserViews(), row.songViews(), row.trailerComments(),
                row.trailerViewsWeekBefore(), row.screens(),
                row.runtime() != null ? row.runtime() : entity.getRuntime(), row.cbfcRating(),
                row.productionCompanies() != null ? row.productionCompanies() : entity.getProductionCompany(),
                lead, row.director() != null ? row.director() : entity.getDirector(), row.musicDirector(),
                row.verdict());
    }

    private static boolean isSelf(BoxOfficeFilm subject, BoxOfficeFilm film) {
        return titleKey(film.title()).equals(titleKey(subject.title()))
                && (subject.releaseDate() == null
                    || daysApart(film.releaseDate(), subject.releaseDate()) <= MATCH_WINDOW_DAYS);
    }

    // ---- box-office cohorts --------------------------------------------------------------------

    private List<PeerCohort> buildCohorts(Context ctx, List<BoxOfficeFilm> films, int lookbackYears) {
        BoxOfficeFilm subject = ctx.subject();
        Money money = ctx.money();
        List<PeerCohort> cohorts = new ArrayList<>();
        List<BoxOfficeFilm> released = films.stream()
                .filter(f -> !f.releaseDate().isAfter(ctx.today()))
                .filter(f -> !isSelf(subject, f))
                .toList();
        LocalDate since = ctx.today().minusYears(lookbackYears);
        String language = subject.originalLanguage();
        String languageName = language == null ? "" : titleCase(language) + " ";
        List<BoxOfficeFilm> sameLanguage = released.stream()
                .filter(f -> language != null && language.equals(f.originalLanguage()))
                .filter(f -> !f.releaseDate().isBefore(since))
                .toList();
        List<Metric> metrics = ctx.upcoming() ? UPCOMING_METRICS : RELEASED_METRICS;

        Double budget = subject.budget();
        if (budget != null) {
            double lo = budget * 0.6;
            double hi = budget * 1.6;
            addCohort(ctx, cohorts, "SIMILAR_BUDGET", "Similar-budget " + languageName + "films",
                    "Original " + languageName + "releases since " + since.getYear() + " with a budget of "
                            + money.format(money.convert(lo), Unit.MONEY) + " to "
                            + money.format(money.convert(hi), Unit.MONEY),
                    filter(sameLanguage, f -> f.budget() != null && f.budget() >= lo && f.budget() <= hi),
                    Comparator.comparingDouble(f -> Math.abs(Math.log(f.budget() / budget))), metrics);
        } else {
            ctx.notes().add("No budget is known for this movie, so the similar-budget comparison was skipped.");
        }

        Double rating = subject.rating();
        if (rating != null && !ctx.upcoming()) {
            addCohort(ctx, cohorts, "SIMILAR_RATING", "Similarly rated " + languageName + "films",
                    String.format(Locale.US, "Original %sreleases since %d rated %.1f to %.1f out of 10",
                            languageName, since.getYear(), rating - 0.5, rating + 0.5),
                    filter(sameLanguage, f -> f.rating() != null && Math.abs(f.rating() - rating) <= 0.5),
                    Comparator.comparingDouble(f -> Math.abs(f.rating() - rating)),
                    metrics.stream().filter(m -> m != RATING).toList());
        }

        Set<String> genres = MoviesDataCollectionQueryServiceImpl.tokenizeGenre(subject.genre());
        if (!genres.isEmpty()) {
            Predicate<BoxOfficeFilm> scale = budget == null ? f -> true
                    : f -> f.budget() != null && f.budget() >= budget / 3 && f.budget() <= budget * 3;
            Comparator<BoxOfficeFilm> closeness = Comparator
                    .comparingLong((BoxOfficeFilm f) -> -sharedGenres(genres, f.genre()));
            if (budget != null) {
                closeness = closeness.thenComparingDouble(f -> Math.abs(Math.log(f.budget() / budget)));
            }
            addCohort(ctx, cohorts, "SIMILAR_GENRE_AND_SCALE", "Same-genre " + languageName + "films",
                    "Original " + languageName + "releases since " + since.getYear() + " sharing a genre with "
                            + subject.genre() + (budget == null ? "" : ", budget within 3x either way"),
                    filter(sameLanguage, f -> MoviesDataCollectionQueryServiceImpl.genreOverlaps(genres, f.genre())
                            && scale.test(f)),
                    closeness, metrics);
        }

        LocalDate before = subject.releaseDate() != null && !subject.releaseDate().isAfter(ctx.today())
                ? subject.releaseDate() : ctx.today().plusDays(1);
        addTalentCohort(ctx, cohorts, "SAME_LEAD_ACTOR", subject.leadActor(), "led by", released, before,
                f -> List.of(nullToEmpty(f.leadActor())), metrics);
        addTalentCohort(ctx, cohorts, "SAME_DIRECTOR", firstName(subject.director()), "directed by", released, before,
                f -> names(f.director()), metrics);
        addTalentCohort(ctx, cohorts, "SAME_MUSIC_DIRECTOR", firstName(subject.musicDirector()), "with music by",
                released, before, f -> names(f.musicDirector()), metrics);
        return cohorts;
    }

    private void addTalentCohort(Context ctx, List<PeerCohort> cohorts, String kind, String person, String role,
                                 List<BoxOfficeFilm> released, LocalDate before,
                                 Function<BoxOfficeFilm, List<String>> credits, List<Metric> metrics) {
        if (person == null || person.isBlank()) {
            return;
        }
        String key = titleKey(person);
        List<BoxOfficeFilm> films = released.stream()
                .filter(f -> credits.apply(f).stream().anyMatch(name -> titleKey(name).equals(key)))
                .filter(f -> f.releaseDate().isBefore(before))
                .sorted(Comparator.comparing(BoxOfficeFilm::releaseDate).reversed())
                .limit(TALENT_FILMS)
                .toList();
        String name = person.trim();
        addCohort(ctx, cohorts, kind, name + "'s previous films",
                "The last " + TALENT_FILMS + " films " + role + " " + name + " before this one, any language",
                films, Comparator.comparing(BoxOfficeFilm::releaseDate).reversed(), metrics);
    }

    private static List<BoxOfficeFilm> filter(List<BoxOfficeFilm> films, Predicate<BoxOfficeFilm> predicate) {
        return films.stream().filter(predicate).toList();
    }

    private void addCohort(Context ctx, List<PeerCohort> cohorts, String kind, String label, String criteria,
                           List<BoxOfficeFilm> peers, Comparator<BoxOfficeFilm> closeness, List<Metric> metrics) {
        if (peers.isEmpty()) {
            ctx.notes().add("No comparable films were found for: " + (TALENT_KINDS.contains(kind) ? label : lowerFirst(label)) + ".");
            return;
        }
        Money money = ctx.money();
        BoxOfficeFilm subject = ctx.subject();
        String phrase = TALENT_KINDS.contains(kind) ? label : lowerFirst(label);
        List<MetricComparison> comparisons = new ArrayList<>();
        for (Metric metric : metrics) {
            Double value = metric.value().apply(subject);
            // Before release a row without the movie's own value says nothing the outcomes don't.
            if (ctx.upcoming() && value == null) {
                continue;
            }
            MetricComparison comparison = compare(metric.key(), metric.label(), metric.unit(), value,
                    peers.stream().map(metric.value()).filter(v -> v != null).toList(), money);
            if (comparison.peersWithData() == 0 && comparison.value() == null) {
                continue;
            }
            comparisons.add(comparison);
            collectPoint(comparison, phrase, money, ctx.strengths(), ctx.weaknesses());
        }
        CohortOutcomes outcomes = outcomes(peers, money);
        if (ctx.upcoming()) {
            collectOutcomePoint(kind, phrase, outcomes, money, ctx);
        }
        List<ComparableFilm> closest = peers.stream()
                .sorted(closeness)
                .limit(CLOSEST_PEERS)
                .map(f -> new ComparableFilm(f.title(), f.releaseDate(), titleCase(f.originalLanguage()),
                        round2(money.convert(f.budget())), round2(money.convert(f.revenue())),
                        round2(f.returnOnBudget()), f.rating(), f.totalVideoViews(), f.verdict(),
                        subject.revenue() == null || f.revenue() == null ? null : subject.revenue() > f.revenue()))
                .toList();
        cohorts.add(new PeerCohort(kind, label, criteria, peers.size(), outcomes, comparisons, closest));
    }

    static CohortOutcomes outcomes(List<BoxOfficeFilm> peers, Money money) {
        List<Double> gross = peers.stream().map(BoxOfficeFilm::revenue).filter(v -> v != null).sorted().toList();
        List<Double> roi = peers.stream().map(BoxOfficeFilm::returnOnBudget).filter(v -> v != null).sorted().toList();
        List<String> verdicts = peers.stream().map(BoxOfficeFilm::verdict)
                .filter(v -> v != null && !v.isBlank()).toList();
        int hits = (int) verdicts.stream().filter("hit"::equals).count();
        Integer hitRate = verdicts.size() >= MIN_PEERS_TO_RANK
                ? (int) Math.round(100.0 * hits / verdicts.size()) : null;
        return new CohortOutcomes(gross.size(),
                gross.isEmpty() ? null : round2(money.convert(quantile(gross, 0.5))),
                gross.isEmpty() ? null : round2(money.convert(quantile(gross, 0.75))),
                roi.isEmpty() ? null : round2(quantile(roi, 0.5)),
                hits, verdicts.size(), hitRate);
    }

    /** Before release: a strong (or weak) track record for the cast, crew or comparable films is the pitch. */
    private static void collectOutcomePoint(String kind, String phrase, CohortOutcomes o, Money money, Context ctx) {
        if (o.hitRate() == null) {
            return;
        }
        String gross = o.medianGross() == null ? ""
                : ", with a median worldwide gross of " + money.format(o.medianGross(), Unit.MONEY);
        String films = withCount(phrase, o.classified());
        // Talent first, then the wider market; within each, the higher hit rate first.
        int score = (TALENT_KINDS.contains(kind) ? 1000 : 0) + o.hitRate();
        if (o.hitRate() >= 50) {
            ctx.outcomeStrengths().add(new Point(kind, score, o.classified(),
                    capitalize(films) + ": " + o.hits() + " of " + o.classified() + " were hits" + gross + "."));
        } else {
            ctx.outcomeWeaknesses().add(new Point(kind, score, o.classified(),
                    "Only " + o.hits() + " of " + films + " were hits" + gross + "."));
        }
        ctx.outcomeStrengths().sort(Comparator.comparingInt(Point::score).reversed());
        ctx.outcomeWeaknesses().sort(Comparator.comparingInt(Point::score).reversed());
    }

    /**
     * Ranks {@code value} against {@code peerValues}. Values are raw (USD for money); the returned
     * value/median/top-quartile are converted for display.
     */
    static MetricComparison compare(String key, String label, Unit unit, Double value, List<Double> peerValues,
                                    Money money) {
        int n = peerValues.size();
        List<Double> sorted = peerValues.stream().sorted().toList();
        Double median = n == 0 ? null : quantile(sorted, 0.5);
        Double topQuartile = n == 0 ? null : quantile(sorted, 0.75);
        Integer percentile = null;
        Integer rank = null;
        if (value != null && n >= MIN_PEERS_TO_RANK) {
            long below = sorted.stream().filter(v -> v < value).count();
            long ties = sorted.stream().filter(v -> v.equals(value)).count();
            percentile = (int) Math.round(100.0 * (below + ties / 2.0) / n);
            rank = 1 + (int) sorted.stream().filter(v -> v > value).count();
        }
        Double multiple = value != null && median != null && median > 0 ? round2(value / median) : null;
        DoubleUnaryOperator display = v -> unit == Unit.MONEY ? money.convert(v) : v;
        return new MetricComparison(key, label, unit.name(),
                value == null ? null : round2(display.applyAsDouble(value)),
                median == null ? null : round2(display.applyAsDouble(median)),
                topQuartile == null ? null : round2(display.applyAsDouble(topQuartile)),
                percentile, rank, n, multiple);
    }

    /** {@code cohortPhrase} reads mid-sentence, e.g. "similar-budget Tamil films". */
    private static void collectPoint(MetricComparison c, String cohortPhrase, Money money,
                                     List<Point> strengths, List<Point> weaknesses) {
        if (c.percentile() == null) {
            return;
        }
        Unit unit = Unit.valueOf(c.unit());
        String value = money.format(c.value(), unit);
        String median = money.format(c.peerMedian(), unit);
        String peers = withCount(cohortPhrase, c.peersWithData());
        if (c.percentile() >= STRONG_PERCENTILE) {
            strengths.add(new Point(c.metric(), c.percentile(), c.peersWithData(),
                    c.label() + " of " + value + " ranks #" + c.rank() + " against " + peers + ", ahead of "
                            + c.percentile() + "% of them (peer median " + median + ")."));
        } else if (c.percentile() <= WEAK_PERCENTILE) {
            weaknesses.add(new Point(c.metric(), 100 - c.percentile(), c.peersWithData(),
                    c.label() + " of " + value + " trails " + (100 - c.percentile()) + "% of " + peers
                            + " (peer median " + median + ")."));
        }
    }

    /** "Suriya's previous films" -> "Suriya's 8 previous films"; otherwise "29 similar-budget Tamil films". */
    private static String withCount(String phrase, int count) {
        int possessive = phrase.indexOf("'s ");
        return possessive >= 0
                ? phrase.substring(0, possessive + 3) + count + " " + phrase.substring(possessive + 3)
                : count + " " + phrase;
    }

    /** The strongest point per metric (larger cohort on a tie), strongest first, capped. */
    private static List<String> topPoints(List<Point> points, boolean strengths) {
        Map<String, Point> best = new LinkedHashMap<>();
        Comparator<Point> preference = Comparator.comparingInt(Point::score).reversed()
                .thenComparing(Comparator.comparingInt(Point::peers).reversed());
        for (Point point : points) {
            best.merge(point.key(), point, (a, b) -> preference.compare(a, b) <= 0 ? a : b);
        }
        return best.values().stream().sorted(preference).limit(MAX_POINTS).map(Point::text).toList();
    }

    // ---- social reach --------------------------------------------------------------------------

    private record Totals(long posts, long positive, long negative, long views, long likes, long comments,
                          long shares) {

        long engagement() {
            return likes + comments + shares;
        }

        Double engagementPerPost() {
            return posts == 0 ? null : round2((double) engagement() / posts);
        }

        Double positiveShare() {
            return posts == 0 ? null : round2(100.0 * positive / posts);
        }

        Double netSentiment() {
            return posts == 0 ? null : round2(100.0 * (positive - negative) / posts);
        }

        Totals plus(Totals o) {
            return new Totals(posts + o.posts, positive + o.positive, negative + o.negative, views + o.views,
                    likes + o.likes, comments + o.comments, shares + o.shares);
        }

        static Totals of(PlatformTotals p) {
            return new Totals(p.posts(), p.positivePosts(), p.negativePosts(), p.views(), p.likes(), p.comments(),
                    p.shares());
        }
    }

    private static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, 0, 0);

    private SocialReach buildSocialReach(Context ctx, ManagedEntity entity, Integer daysToRelease) {
        String selfKey = titleKey(entity.getName());
        // Before release, peers are compared at the same stage, so they must already be that close to
        // (or past) their own release.
        boolean sameStage = ctx.upcoming() && daysToRelease != null;
        List<ManagedEntity> peers = entity.getLanguage() == null ? List.of()
                : managedEntityRepository.findByTypeAndLanguageIgnoreCase(EntityType.MOVIE.name(), entity.getLanguage())
                        .stream()
                        .filter(p -> !p.getId().equals(entity.getId()) && !titleKey(p.getName()).equals(selfKey))
                        .filter(p -> !sameStage || (p.getReleaseDate() != null
                                && !p.getReleaseDate().minusDays(daysToRelease).isAfter(ctx.today())))
                        .toList();
        List<Long> ids = Stream.concat(Stream.of(entity.getId()), peers.stream().map(ManagedEntity::getId)).toList();

        Map<Long, Totals> totals = new HashMap<>();
        List<PlatformReach> platforms = new ArrayList<>();
        // The movie's own figures are always its posts so far; only peers are cut at the same stage.
        List<PlatformTotals> rows = new ArrayList<>(socialReachLookup.totalsByPlatform(List.of(entity.getId()), null));
        if (!peers.isEmpty()) {
            List<Long> peerIds = ids.subList(1, ids.size());
            rows.addAll(socialReachLookup.totalsByPlatform(peerIds, sameStage ? daysToRelease : null));
        }
        for (PlatformTotals row : rows) {
            totals.merge(row.entityId(), Totals.of(row), Totals::plus);
            if (row.entityId().equals(entity.getId())) {
                platforms.add(new PlatformReach(row.platform(), row.posts(), row.views(),
                        row.likes() + row.comments() + row.shares()));
            }
        }
        platforms.sort(Comparator.comparingLong(PlatformReach::posts).reversed());
        Totals self = totals.getOrDefault(entity.getId(), EMPTY);

        // The same film tracked twice (e.g. "Balan The Boy" / "Balan: The Boy") counts once: its busiest copy.
        Map<String, Totals> peerTotals = new HashMap<>();
        for (ManagedEntity peer : peers) {
            Totals t = totals.get(peer.getId());
            if (t != null && t.posts() > 0) {
                peerTotals.merge(titleKey(peer.getName()), t, (a, b) -> a.posts() >= b.posts() ? a : b);
            }
        }
        List<Totals> peerList = List.copyOf(peerTotals.values());
        String languageName = entity.getLanguage() == null ? "" : titleCase(entity.getLanguage()) + " ";
        String cohortPhrase = sameStage
                ? "tracked " + languageName + "movies at the same stage before their release"
                : "tracked " + languageName + "movies";
        String basis = sameStage
                ? "Other tracked " + languageName + "movies' posts up to " + daysToRelease
                        + " days before their own release, against this movie's posts so far"
                : "All tracked posts for each movie";

        List<MetricComparison> comparisons = new ArrayList<>();
        if (self.posts() > 0 && peerList.isEmpty()) {
            ctx.notes().add(sameStage
                    ? "No other tracked " + languageName + "movie has posts from " + daysToRelease
                            + " days before its release, so this movie's buzz isn't ranked yet."
                    : "No other tracked " + languageName + "movie has posts yet, so social reach isn't ranked.");
        }
        if (self.posts() > 0 && !peerList.isEmpty()) {
            String volumeLabel = ctx.upcoming() ? "Pre-release post volume" : "Social post volume";
            addSocial(comparisons, "posts", volumeLabel, Unit.COUNT, (double) self.posts(),
                    peerList, t -> (double) t.posts(), cohortPhrase, ctx);
            addSocial(comparisons, "views", "Social views", Unit.COUNT, (double) self.views(),
                    peerList, t -> (double) t.views(), cohortPhrase, ctx);
            addSocial(comparisons, "engagement", "Social engagement (likes, comments, shares)", Unit.COUNT,
                    (double) self.engagement(), peerList, t -> (double) t.engagement(), cohortPhrase, ctx);
            addSocial(comparisons, "engagementPerPost", "Engagement per post", Unit.COUNT, self.engagementPerPost(),
                    peerList, Totals::engagementPerPost, cohortPhrase, ctx);
            addSocial(comparisons, "positiveShare", "Positive share of posts", Unit.PERCENT, self.positiveShare(),
                    peerList, Totals::positiveShare, cohortPhrase, ctx);
        }
        return new SocialReach(self.posts(), self.views(), self.likes(), self.comments(), self.shares(),
                self.engagement(), self.engagementPerPost(), self.positiveShare(), self.netSentiment(),
                platforms, peerList.size(), basis, comparisons);
    }

    private static void addSocial(List<MetricComparison> out, String key, String label, Unit unit, Double value,
                                  List<Totals> peers, Function<Totals, Double> metric, String cohortPhrase,
                                  Context ctx) {
        MetricComparison c = compare(key, label, unit, value,
                peers.stream().map(metric).filter(v -> v != null).toList(), ctx.money());
        out.add(c);
        collectPoint(c, cohortPhrase, ctx.money(), ctx.strengths(), ctx.weaknesses());
    }

    // ---- profile -------------------------------------------------------------------------------

    private static MovieProfile profile(ManagedEntity entity, BoxOfficeFilm s, boolean matched, boolean upcoming,
                                        Integer daysToRelease, Money money) {
        return new MovieProfile(entity.getId(), entity.getName(), entity.getLanguage(), entity.getIndustry(),
                s.genre(), s.releaseDate(), upcoming ? UPCOMING : RELEASED, daysToRelease, s.runtime(),
                s.cbfcRating(), s.languages().stream().map(RightsValuationReportService::titleCase).toList(),
                s.screens(), s.productionCompanies(), s.leadActor(), s.director(), s.musicDirector(),
                round2(money.convert(s.budget())), round2(money.convert(s.revenue())), round2(s.returnOnBudget()),
                s.rating(), s.trailerViews(), s.teaserViews(), s.songViews(), s.totalVideoViews(),
                s.trailerComments(), s.trailerViewsWeekBefore(), matched);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Linear-interpolated quantile of an ascending list. */
    static double quantile(List<Double> sorted, double q) {
        double pos = q * (sorted.size() - 1);
        int lower = (int) Math.floor(pos);
        int upper = (int) Math.ceil(pos);
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * (pos - lower);
    }

    /** Lower-case letters and digits only, so "Balan: The Boy" and "Balan The Boy" (or "S. S." and "S.S.") match. */
    static String titleKey(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Director / music-director columns can list several comma-separated names. */
    private static List<String> names(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Stream.of(value.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList();
    }

    private static String firstName(String value) {
        List<String> names = names(value);
        return names.isEmpty() ? null : names.get(0);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long sharedGenres(Set<String> genres, String filmGenre) {
        return MoviesDataCollectionQueryServiceImpl.tokenizeGenre(filmGenre).stream().filter(genres::contains).count();
    }

    private static long daysApart(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    private static Double positive(Double value) {
        return value != null && value > 0 ? value : null;
    }

    private static Double toDouble(Long value) {
        return value == null ? null : value.doubleValue();
    }

    private static Double round2(Double value) {
        return value == null ? null : Math.round(value * 100) / 100.0;
    }

    private static String titleCase(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + trimmed.substring(1);
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static String lowerFirst(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }
}
