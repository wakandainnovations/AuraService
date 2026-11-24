package com.aura.service.service;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads films from {@code india_movies_data_collection} for the rights-valuation report. An interface
 * so the report's comparison logic can be tested with a lambda (EntityManager can't be mocked on this
 * project's Java version).
 */
public interface BoxOfficeFilmLookup {

    /**
     * One film. Money is in USD, as stored. Zeros in the table mean "unknown" and come back as null.
     *
     * @param originalLanguage lower-case first language the film is listed in
     * @param languages        every language it was released in, lower-case
     * @param verdict          the dataset's {@code hit_flop_avg}: "hit", "avg" or "flop", or null
     */
    record BoxOfficeFilm(String title, LocalDate releaseDate, String originalLanguage, List<String> languages,
                         String genre, Double rating, Double budget, Double revenue, Long trailerViews,
                         Long teaserViews, Long songViews, Long trailerComments, Long trailerViewsWeekBefore,
                         Integer screens, Integer runtime, String cbfcRating, String productionCompanies,
                         String leadActor, String director, String musicDirector, String verdict) {

        public Double returnOnBudget() {
            return budget != null && revenue != null && budget > 0 ? revenue / budget : null;
        }

        /** Trailer + teaser + song views; null when none is known. */
        public Long totalVideoViews() {
            if (trailerViews == null && teaserViews == null && songViews == null) {
                return null;
            }
            return orZero(trailerViews) + orZero(teaserViews) + orZero(songViews);
        }

        private static long orZero(Long value) {
            return value == null ? 0 : value;
        }
    }

    /** Every film with a full release date, one per (title, release date). */
    List<BoxOfficeFilm> datedFilms();
}
