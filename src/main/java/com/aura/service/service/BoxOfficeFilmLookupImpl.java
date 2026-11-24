package com.aura.service.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native query over {@code india_movies_data_collection}. The table's key includes language, so a
 * dubbed film can have several rows; they're collapsed to one per (title, release date), taking the
 * largest known number for each metric and the union of the languages. The original language is the
 * first one listed on {@code MIN(language)}, as {@code SimilarBudgetTalentLookupImpl} does. The whole
 * table is a few thousand films, so cohorts are filtered in Java.
 */
@Service
public class BoxOfficeFilmLookupImpl implements BoxOfficeFilmLookup {

    // release_date is free text: a bare year or an unpadded "2026-07-3" also appear. Only full dates
    // are kept and parsed in Java. Zero is the column default, so NULLIF turns it back into unknown.
    private static final String DATED_FILMS_SQL =
            "SELECT MIN(movie_name), release_date, MIN(language), string_agg(language, ',')," +
            "    MIN(genre), MAX(NULLIF(rating_10, 0)), MAX(NULLIF(budget, 0)), MAX(NULLIF(revenue, 0))," +
            "    MAX(NULLIF(trailer_views, 0)), MAX(NULLIF(teaser_views, 0)), MAX(NULLIF(song_views, 0))," +
            "    MAX(NULLIF(trailer_comments, 0)), MAX(NULLIF(trailer_views_7d_prior_to_release, 0))," +
            "    MAX(NULLIF(number_of_screens, 0)), MAX(NULLIF(runtime, 0)), MIN(cbfc_rating)," +
            "    MIN(production_companies), MIN(lead_actor_1), MIN(director), MIN(music_director)," +
            "    MIN(LOWER(TRIM(hit_flop_avg)))" +
            " FROM india_movies_data_collection" +
            " WHERE release_date ~ '^[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}$'" +
            " GROUP BY LOWER(TRIM(movie_name)), release_date";

    private static final DateTimeFormatter LENIENT_DATE = DateTimeFormatter.ofPattern("yyyy-M-d");

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<BoxOfficeFilm> datedFilms() {
        List<Object[]> rows = entityManager.createNativeQuery(DATED_FILMS_SQL).getResultList();
        List<BoxOfficeFilm> films = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            LocalDate releaseDate = parseDate((String) row[1]);
            if (releaseDate == null) {
                continue;
            }
            List<String> languages = languages((String) row[3]);
            String original = languages((String) row[2]).stream().findFirst().orElse(null);
            films.add(new BoxOfficeFilm((String) row[0], releaseDate, original, languages, (String) row[4],
                    toDouble(row[5]), toDouble(row[6]), toDouble(row[7]), toLong(row[8]), toLong(row[9]),
                    toLong(row[10]), toLong(row[11]), toLong(row[12]), toInt(row[13]), toInt(row[14]),
                    blankToNull(row[15]), blankToNull(row[16]), blankToNull(row[17]), blankToNull(row[18]),
                    blankToNull(row[19]), blankToNull(row[20])));
        }
        return films;
    }

    static LocalDate parseDate(String value) {
        try {
            return value == null ? null : LocalDate.parse(value.trim(), LENIENT_DATE);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** Comma-separated language lists, lower-cased and de-duplicated in listed order. */
    static List<String> languages(String value) {
        Set<String> languages = new LinkedHashSet<>();
        if (value != null) {
            for (String token : value.split(",")) {
                String language = token.trim().toLowerCase(Locale.ROOT);
                if (!language.isEmpty()) {
                    languages.add(language);
                }
            }
        }
        return List.copyOf(languages);
    }

    private static Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer toInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static String blankToNull(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
}
