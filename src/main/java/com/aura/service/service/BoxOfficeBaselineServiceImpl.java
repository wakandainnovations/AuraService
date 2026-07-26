package com.aura.service.service;

import com.aura.service.dto.BoxOfficeMovieBaseline;
import com.aura.service.dto.IndianMacroSnapshot;
import com.aura.service.dto.MovieBacktestRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements B0 = adjustedBudget * (rStar + rDirector + rConcept) * rIP, all from real columns in
 * {@code movies_data_collection}/{@code actors_data_collection} (neither has a JPA entity - same
 * native-query convention as {@link IndianMacroEconomicDataServiceImpl}/
 * {@code BoxOfficeBacktestServiceImpl}). Every sub-formula here is a documented heuristic, not a
 * derived economic model - each method's comment states the judgment call it makes so a reviewer
 * can see exactly what "popularity" or "concept novelty" was assumed to mean.
 */
@Slf4j
@Service
public class BoxOfficeBaselineServiceImpl implements BoxOfficeBaselineService {

    // Weight applied to each cast member's individual popularity score when averaging into rStar,
    // keyed by their role_position in actors_data_collection - per the given formula: the lead
    // (position 1) counts fully, the second-billed actor counts less, and anyone billed third or
    // lower barely moves the number.
    private static final double LEAD_WEIGHT = 1.0;
    private static final double SECOND_BILLED_WEIGHT = 0.4;
    private static final double SUPPORTING_WEIGHT = 0.15;

    // "A newcomer will have a score of 0.1 to 0.25" - used whenever there's no prior filmography
    // to compute a real popularity score from (new actor/director, or a blank director field).
    private static final double NEWCOMER_SCORE = 0.15;

    private static final double MIN_TALENT_SCORE = 0.1;
    private static final double MAX_TALENT_SCORE = 1.2;

    // Nominal fallback when budget is missing/zero on an otherwise-eligible row (the backtest's
    // eligibility filter only requires actual gross data, not budget) - B0 would otherwise
    // collapse to exactly 0 and silently zero out the whole prediction. Deliberately small so a
    // missing budget still reads as an outlier in the log rather than a plausible number.
    private static final double FALLBACK_BUDGET_USD = 2_000_000.0;

    // Bounds on the present-value adjustment so a movie from 2001 doesn't get an absurd 20-year
    // compounded multiplier from a handful of high-inflation years with thin data.
    private static final double MIN_PV_FACTOR = 0.5;
    private static final double MAX_PV_FACTOR = 8.0;

    // Trailing window for "movies on a similar genre combination released close to this one" used
    // by rConcept - 24 months, scoped to the same language (a Tamil rom-com's real competition is
    // other Tamil rom-coms, not a Telugu one).
    private static final int CONCEPT_WINDOW_MONTHS = 24;

    // similarity() threshold (pg_trgm, already indexed via idx_movies_data_collection_trgm) for
    // treating an earlier title as "the same franchise" for rIP - e.g. "Movie 2" vs "Movie",
    // "Movie: Part One" vs "Movie: Part Two".
    private static final double FRANCHISE_SIMILARITY_THRESHOLD = 0.4;

    private static final String ACTOR_HISTORY_SQL =
            "SELECT AVG(rating), SUM(COALESCE(votes,0)), COUNT(*) FROM actors_data_collection " +
            "WHERE LOWER(actor_name) = LOWER(:actorName) AND release_date < :releaseDate AND rating IS NOT NULL";

    private static final String CAST_WITH_POSITION_SQL =
            "SELECT actor_name, role_position FROM actors_data_collection " +
            "WHERE LOWER(movie_name) = LOWER(:movieName) AND release_date = :releaseDate " +
            "ORDER BY role_position ASC NULLS LAST LIMIT 6";

    private static final String DIRECTOR_HISTORY_SQL =
            "SELECT AVG(imdb_rating), COUNT(*) FROM movies_data_collection " +
            "WHERE directors ILIKE CONCAT('%', :directorName, '%') AND release_date < :releaseDate " +
            "AND imdb_rating IS NOT NULL AND imdb_rating <> 0";

    private static final String CONCEPT_DENSITY_SQL =
            "SELECT COUNT(*) FROM movies_data_collection " +
            "WHERE genre = :genre AND LOWER(language) = LOWER(:language) " +
            "AND release_date < :releaseDate AND release_date >= :windowStart";

    private static final String FRANCHISE_MATCH_SQL =
            "SELECT budget, revenue FROM movies_data_collection " +
            "WHERE release_date < :releaseDate AND movie_name <> :movieName " +
            "AND similarity(movie_name, :movieName) > :threshold " +
            "ORDER BY similarity(movie_name, :movieName) DESC LIMIT 1";

    @PersistenceContext
    private EntityManager entityManager;

    private final IndianMacroEconomicDataService macroEconomicDataService;
    private final Map<Integer, IndianMacroSnapshot> macroByYearCache = new ConcurrentHashMap<>();

    public BoxOfficeBaselineServiceImpl(IndianMacroEconomicDataService macroEconomicDataService) {
        this.macroEconomicDataService = macroEconomicDataService;
    }

    @Override
    public BoxOfficeMovieBaseline computeBaseline(MovieBacktestRow row) {
        Integer releaseYear = parseYear(row.releaseDate());
        double adjustedBudget = presentValueBudget(row.budget(), releaseYear, row.gdpUsdBillions());
        double rStar = computeRStar(row.movieName(), row.releaseDate());
        double rDirector = computeRDirector(row.director(), row.releaseDate());
        double rConcept = computeRConcept(row.genre(), row.language(), row.releaseDate());
        double rIP = computeRIP(row.movieName(), row.releaseDate());

        double b0 = adjustedBudget * (rStar + rDirector + rConcept) * rIP;
        return new BoxOfficeMovieBaseline(adjustedBudget, rStar, rDirector, rConcept, rIP, b0);
    }

    // Compounds India's inflation rate year-over-year from the release year to the present, then
    // separately scales by the ratio of present-day to release-year GDP - both requested
    // explicitly ("adjusted to present value using inflation_rate_pct and normalized against
    // gdp_usd_billions... for historical consistency"). Applying both is a deliberate double lever
    // (inflation for currency erosion, GDP ratio for the whole market having grown), not an
    // oversight; the combined factor is clamped to keep multi-decade compounding sane.
    private double presentValueBudget(Double budget, Integer releaseYear, Double releaseYearGdp) {
        double baseBudget = (budget == null || budget <= 0) ? FALLBACK_BUDGET_USD : budget;
        if (releaseYear == null) {
            return baseBudget;
        }

        int presentYear = LocalDate.now().getYear();
        double inflationFactor = 1.0;
        for (int year = releaseYear + 1; year <= presentYear; year++) {
            IndianMacroSnapshot snapshot = macroForYear(year);
            if (snapshot != null && snapshot.inflationRatePct() != null) {
                inflationFactor *= (1 + snapshot.inflationRatePct() / 100.0);
            }
        }
        inflationFactor = clamp(inflationFactor, MIN_PV_FACTOR, MAX_PV_FACTOR);

        double gdpRatio = 1.0;
        IndianMacroSnapshot presentSnapshot = macroForYear(presentYear);
        if (presentSnapshot != null && presentSnapshot.gdpUsdBillions() != null
                && releaseYearGdp != null && releaseYearGdp > 0) {
            gdpRatio = presentSnapshot.gdpUsdBillions() / releaseYearGdp;
        }
        gdpRatio = clamp(gdpRatio, MIN_PV_FACTOR, MAX_PV_FACTOR);

        return baseBudget * inflationFactor * gdpRatio;
    }

    private IndianMacroSnapshot macroForYear(int year) {
        return macroByYearCache.computeIfAbsent(year,
                y -> macroEconomicDataService.lookup(LocalDate.of(y, 6, 30)));
    }

    // rStar = role-position-weighted average of each cast member's own historical popularity
    // score (not a sum across the cast - that would make rStar grow unboundedly with cast size,
    // contradicting the 0.1-1.2ish per-actor band the score is defined on).
    @SuppressWarnings("unchecked")
    private double computeRStar(String movieName, String releaseDate) {
        List<Object[]> cast = entityManager.createNativeQuery(CAST_WITH_POSITION_SQL)
                .setParameter("movieName", movieName)
                .setParameter("releaseDate", releaseDate)
                .getResultList();
        if (cast.isEmpty()) {
            return NEWCOMER_SCORE;
        }

        double weightedSum = 0;
        double weightTotal = 0;
        for (Object[] row : cast) {
            String actorName = (String) row[0];
            Integer position = row[1] == null ? null : ((Number) row[1]).intValue();
            double weight = position == null || position >= 3 ? SUPPORTING_WEIGHT
                    : position == 2 ? SECOND_BILLED_WEIGHT : LEAD_WEIGHT;
            weightedSum += actorPopularityScore(actorName, releaseDate) * weight;
            weightTotal += weight;
        }
        return weightTotal == 0 ? NEWCOMER_SCORE : clamp(weightedSum / weightTotal, MIN_TALENT_SCORE, MAX_TALENT_SCORE);
    }

    @SuppressWarnings("unchecked")
    private double actorPopularityScore(String actorName, String beforeReleaseDate) {
        List<Object[]> rows = entityManager.createNativeQuery(ACTOR_HISTORY_SQL)
                .setParameter("actorName", actorName)
                .setParameter("releaseDate", beforeReleaseDate)
                .getResultList();
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return NEWCOMER_SCORE;
        }
        Object[] row = rows.get(0);
        double avgRating = ((Number) row[0]).doubleValue();
        long totalVotes = row[1] == null ? 0 : ((Number) row[1]).longValue();
        return talentScoreFromRatingAndVolume(avgRating, totalVotes);
    }

    // rDirector uses the same shape of formula as rStar's per-actor score, but sourced from
    // movies_data_collection's imdb_rating for films the director's name appears in (directors is
    // a free-text, sometimes comma-joined column - ILIKE substring match is a deliberate looseness
    // to catch multi-director credits at the cost of occasional false positives on short names).
    @SuppressWarnings("unchecked")
    private double computeRDirector(String director, String releaseDate) {
        if (director == null || director.isBlank()) {
            return NEWCOMER_SCORE;
        }
        List<Object[]> rows = entityManager.createNativeQuery(DIRECTOR_HISTORY_SQL)
                .setParameter("directorName", director.trim())
                .setParameter("releaseDate", releaseDate)
                .getResultList();
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return NEWCOMER_SCORE;
        }
        Object[] row = rows.get(0);
        double avgRating = ((Number) row[0]).doubleValue();
        long movieCount = row[1] == null ? 0 : ((Number) row[1]).longValue();
        // No per-movie vote totals available for directors the way actors_data_collection has
        // per-actor votes, so prior movie count stands in as the volume/confidence signal instead.
        return talentScoreFromRatingAndVolume(avgRating, movieCount * 1000L);
    }

    // ratingComponent assumes a 0-10 scale (imdb_rating/actors_data_collection.rating are both
    // IMDB-style); volumeBonus rewards a track record with real audience reach, capped so a single
    // viral outlier can't push the score past the "popular" ceiling on its own.
    private double talentScoreFromRatingAndVolume(double avgRatingOutOf10, long volumeSignal) {
        double ratingComponent = clamp(avgRatingOutOf10 / 10.0, 0.0, 1.0);
        double volumeBonus = Math.min(0.25, Math.log10(1 + volumeSignal) / 20.0);
        return clamp(ratingComponent + volumeBonus, MIN_TALENT_SCORE, MAX_TALENT_SCORE);
    }

    // Inverse of competing-title density: more movies already covering the same genre combination
    // in the trailing window before release make the concept less novel. Each competing title
    // knocks 0.05 off a 0.9 ceiling; floored at 0.1 so a saturated genre never goes fully to zero
    // (the movie could still execute the familiar concept well).
    @SuppressWarnings("unchecked")
    private double computeRConcept(String genre, String language, String releaseDate) {
        if (genre == null || genre.isBlank() || language == null || language.isBlank()) {
            return 0.5;
        }
        LocalDate parsedRelease = parseLocalDate(releaseDate);
        String windowStart = parsedRelease != null
                ? parsedRelease.minusMonths(CONCEPT_WINDOW_MONTHS).toString()
                : "0000-01-01";

        List<Object> rows = entityManager.createNativeQuery(CONCEPT_DENSITY_SQL)
                .setParameter("genre", genre)
                .setParameter("language", language)
                .setParameter("releaseDate", releaseDate)
                .setParameter("windowStart", windowStart)
                .getResultList();
        long count = rows.isEmpty() || rows.get(0) == null ? 0 : ((Number) rows.get(0)).longValue();
        return clamp(0.9 - 0.05 * count, 0.1, 0.9);
    }

    // Detects a franchise/sequel predecessor via trigram title similarity (same extension already
    // backing idx_movies_data_collection_trgm) rather than exact string matching, since sequels are
    // rarely named identically ("Movie" vs "Movie 2" vs "Movie: Part One"). A well-performing
    // predecessor (high ROI) nudges rIP above 1.0; a flop predecessor nudges it below. No match, or
    // a match with no usable budget/revenue, leaves rIP neutral at 1.0 - this movie is treated as
    // having no IP effect either way rather than guessing.
    @SuppressWarnings("unchecked")
    private double computeRIP(String movieName, String releaseDate) {
        if (movieName == null || movieName.isBlank()) {
            return 1.0;
        }
        List<Object[]> rows = entityManager.createNativeQuery(FRANCHISE_MATCH_SQL)
                .setParameter("movieName", movieName)
                .setParameter("releaseDate", releaseDate)
                .setParameter("threshold", FRANCHISE_SIMILARITY_THRESHOLD)
                .getResultList();
        if (rows.isEmpty()) {
            return 1.0;
        }
        Object[] row = rows.get(0);
        Double priorBudget = row[0] == null ? null : ((Number) row[0]).doubleValue();
        Double priorRevenue = row[1] == null ? null : ((Number) row[1]).doubleValue();
        if (priorBudget == null || priorBudget <= 0 || priorRevenue == null || priorRevenue <= 0) {
            return 1.0;
        }
        double priorRoi = priorRevenue / priorBudget;
        return clamp(1.0 + (priorRoi - 2.0) * 0.05, 0.85, 1.4);
    }

    private Integer parseYear(String releaseDateText) {
        if (releaseDateText == null || releaseDateText.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(releaseDateText.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseLocalDate(String releaseDateText) {
        try {
            return LocalDate.parse(releaseDateText);
        } catch (DateTimeParseException e) {
            Integer year = parseYear(releaseDateText);
            return year == null ? null : LocalDate.of(year, 6, 30);
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
