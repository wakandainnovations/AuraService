package com.aura.service.service;

import com.aura.service.dto.BacktestRunStatus;
import com.aura.service.dto.MovieBacktestRow;
import com.aura.service.dto.MovieIdentifier;
import com.aura.service.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Selects the eligible movie set synchronously (a handful of fast, indexed-enough native queries
 * against {@code movies_data_collection}/{@code actors_data_collection} - neither has a JPA
 * entity, matching the convention already established by {@link IndianMacroEconomicDataServiceImpl}
 * for this bulk reference dataset), then hands the slow per-movie LLM work off to
 * {@link BoxOfficeBacktestWorker}. Run state lives in an in-memory map, same pattern as the
 * scoring caches in {@code AnalyticsController} - this is an admin/ops tool, not something that
 * needs to survive a restart.
 */
@Service
public class BoxOfficeBacktestServiceImpl implements BoxOfficeBacktestService {

    private static final int DEFAULT_LIMIT = 50;

    // Indian-language rows released after 2000, with at least one populated actual-gross column -
    // per docs/PREDICTIVE_FACTOR_DATA_AUDIT.md, that's the only subset "reality" can be checked
    // against - AND a real budget (budget > 0). The baseline formula B0 = adjustedBudget * (...)
    // needs a real budget to mean anything; without this filter, movies with budget=0/null would
    // fall back to a nominal floor estimate that has no grounding in that specific movie's actual
    // scale (see the FALLBACK_BUDGET_USD comment in BoxOfficeBaselineServiceImpl - it still exists
    // as a defensive fallback for rerunMovies() call with an arbitrary movie list, but the normal
    // eligibility path here should never need it). release_date is a free-text column (sometimes
    // just a year, occasionally malformed like "XIII"), hence the regex guard before the year cast.
    private static final String ELIGIBLE_MOVIES_SQL =
            "SELECT movie_name, release_date, genre, language, release_day, gdp_usd_billions, " +
            "inflation_rate_pct, budget, production_companies, runtime, directors, synopsis, " +
            "first_song_release_date, song_views, teaser_release_date, teaser_views, " +
            "trailer_release_date, trailer_views, revenue, india_gross_collection_usd, " +
            "domestic_collection_usd, overseas_collection_usd, first_day_worldwide_usd " +
            "FROM movies_data_collection " +
            "WHERE LOWER(language) IN ('hindi','tamil','telugu','malayalam','kannada') " +
            "AND release_date ~ '^[0-9]{4}' " +
            "AND CAST(SUBSTRING(release_date FROM 1 FOR 4) AS INTEGER) > 2000 " +
            "AND COALESCE(budget,0) > 0 " +
            "AND (COALESCE(revenue,0) > 0 OR COALESCE(india_gross_collection_usd,0) > 0 " +
            "OR COALESCE(domestic_collection_usd,0) > 0 OR COALESCE(overseas_collection_usd,0) > 0) " +
            "ORDER BY release_date DESC LIMIT :limit";

    // Same column list as ELIGIBLE_MOVIES_SQL, but pinned to one exact (movie_name, release_date)
    // pair rather than the eligibility filter - used by rerun() so a prompt-catalog change can be
    // validated against literally the same movie set as a prior run, instead of trusting that a
    // fresh ORDER BY ... LIMIT query happens to return identical rows.
    private static final String MOVIE_BY_IDENTIFIER_SQL =
            "SELECT movie_name, release_date, genre, language, release_day, gdp_usd_billions, " +
            "inflation_rate_pct, budget, production_companies, runtime, directors, synopsis, " +
            "first_song_release_date, song_views, teaser_release_date, teaser_views, " +
            "trailer_release_date, trailer_views, revenue, india_gross_collection_usd, " +
            "domestic_collection_usd, overseas_collection_usd, first_day_worldwide_usd " +
            "FROM movies_data_collection " +
            "WHERE movie_name = :movieName AND release_date = :releaseDate LIMIT 1";

    private static final String CAST_SQL =
            "SELECT actor_name FROM actors_data_collection " +
            "WHERE LOWER(movie_name) = LOWER(:movieName) AND release_date = :releaseDate " +
            "ORDER BY role_position ASC NULLS LAST LIMIT 6";

    @PersistenceContext
    private EntityManager entityManager;

    private final BoxOfficeBacktestWorker worker;
    private final Map<String, BacktestRunStatus> runs = new ConcurrentHashMap<>();

    @Value("${backtest.log.dir:logs/box-office-backtest}")
    private String logDir;

    public BoxOfficeBacktestServiceImpl(BoxOfficeBacktestWorker worker) {
        this.worker = worker;
    }

    @Override
    public BacktestRunStatus startRun(Integer limit) {
        int effectiveLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : limit;
        return launch(fetchEligibleMovies(effectiveLimit));
    }

    @Override
    public BacktestRunStatus getStatus(String runId) {
        return runs.get(runId);
    }

    @Override
    public BacktestRunStatus rerun(String originalRunId) {
        BacktestRunStatus original = runs.get(originalRunId);
        if (original == null) {
            throw new ResourceNotFoundException("No backtest run found with id: " + originalRunId);
        }
        List<MovieIdentifier> identifiers = original.getResults().stream()
                .map(r -> new MovieIdentifier(r.movieName(), r.releaseDate()))
                .collect(Collectors.toList());
        return rerunMovies(identifiers);
    }

    @Override
    public BacktestRunStatus rerunMovies(List<MovieIdentifier> movieIdentifiers) {
        List<MovieBacktestRow> movies = new ArrayList<>(movieIdentifiers.size());
        for (MovieIdentifier id : movieIdentifiers) {
            MovieBacktestRow row = fetchMovieByIdentifier(id.movieName(), id.releaseDate());
            if (row != null) {
                movies.add(row);
            }
        }
        return launch(movies);
    }

    private BacktestRunStatus launch(List<MovieBacktestRow> movies) {
        String runId = UUID.randomUUID().toString();
        String logFilePath = logDir + "/box-office-backtest-" + runId + ".log";
        BacktestRunStatus status = new BacktestRunStatus(runId, movies.size(), logFilePath);
        runs.put(runId, status);
        worker.processAsync(runId, status, movies);
        return status;
    }

    @SuppressWarnings("unchecked")
    private List<MovieBacktestRow> fetchEligibleMovies(int limit) {
        List<Object[]> rows = entityManager.createNativeQuery(ELIGIBLE_MOVIES_SQL)
                .setParameter("limit", limit)
                .getResultList();

        List<MovieBacktestRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(mapRow(row));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private MovieBacktestRow fetchMovieByIdentifier(String movieName, String releaseDate) {
        List<Object[]> rows = entityManager.createNativeQuery(MOVIE_BY_IDENTIFIER_SQL)
                .setParameter("movieName", movieName)
                .setParameter("releaseDate", releaseDate)
                .getResultList();
        return rows.isEmpty() ? null : mapRow(rows.get(0));
    }

    private MovieBacktestRow mapRow(Object[] row) {
        String movieName = (String) row[0];
        String releaseDate = (String) row[1];
        return new MovieBacktestRow(
                movieName,
                releaseDate,
                (String) row[2],
                (String) row[3],
                (String) row[4],
                toDouble(row[5]),
                toDouble(row[6]),
                toDouble(row[7]),
                (String) row[8],
                toInt(row[9]),
                (String) row[10],
                fetchCast(movieName, releaseDate),
                (String) row[11],
                (String) row[12],
                toLong(row[13]),
                (String) row[14],
                toLong(row[15]),
                (String) row[16],
                toLong(row[17]),
                toDouble(row[18]),
                toDouble(row[19]),
                toDouble(row[20]),
                toDouble(row[21]),
                toDouble(row[22]));
    }

    @SuppressWarnings("unchecked")
    private String fetchCast(String movieName, String releaseDate) {
        List<Object> actorNames = entityManager.createNativeQuery(CAST_SQL)
                .setParameter("movieName", movieName)
                .setParameter("releaseDate", releaseDate)
                .getResultList();
        if (actorNames.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object name : actorNames) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
        }
        return sb.toString();
    }

    private Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Integer toInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
