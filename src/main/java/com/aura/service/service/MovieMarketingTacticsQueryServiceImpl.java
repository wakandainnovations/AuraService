package com.aura.service.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw {@code movie_marketing_tactics} native query, following the same {@code @PersistenceContext
 * EntityManager} native-query style as {@link MoviesDataCollectionQueryServiceImpl}. No JPA
 * {@code @Entity} backs {@code movie_marketing_tactics} for the same reason
 * {@code movies_data_collection} has none - this is an externally-populated table, and
 * {@code ddl-auto=update} should never attempt to reconcile Hibernate's idea of its schema against
 * it (see that class's javadoc). {@code tactic_details text[]} is flattened to a single string with
 * {@code array_to_string} in SQL, sidestepping Postgres array/JDBC type mapping entirely.
 *
 * <p>{@code movie_marketing_tactics} carries no genre column of its own, so genre-comparability is
 * resolved by joining to {@code movies_data_collection} on movie name + language + release year, then
 * reusing {@link MoviesDataCollectionQueryServiceImpl#tokenizeGenre}/{@code #genreOverlaps} - the
 * exact same cross-taxonomy comparator {@code movies_data_collection}-backed candidates already use,
 * since the comp table's genre format differs from this platform's own {@code ManagedEntity.genre}.
 */
@Service
public class MovieMarketingTacticsQueryServiceImpl implements MovieMarketingTacticsQueryService {

    // release_date is free-text and not always a full ISO date (see MoviesDataCollectionQueryServiceImpl's
    // RELEASE_DAY_OF_WEEK_SQL comment) - LEFT(...,4) tolerates a bare year like "2025" too, unlike a
    // CAST(... AS date) which would reject it.
    private static final String PEER_TACTICS_SQL =
            "SELECT t.movie_name, t.release_year, t.main_classification_name, t.sub_classification_name, " +
            "       array_to_string(t.tactic_details, ' '), m.genre " +
            "FROM movie_marketing_tactics t " +
            "JOIN movies_data_collection m " +
            "  ON LOWER(m.movie_name) = LOWER(t.movie_name) " +
            "  AND LOWER(m.language) = LOWER(t.language) " +
            "  AND LEFT(m.release_date, 4) = t.release_year " +
            "WHERE LOWER(t.language) = LOWER(:language)";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> findPeerTactics(String genre, String language) {
        var genreTokens = MoviesDataCollectionQueryServiceImpl.tokenizeGenre(genre);
        if (genreTokens.isEmpty() || language == null || language.isBlank()) {
            return List.of();
        }
        List<Object[]> rows = entityManager.createNativeQuery(PEER_TACTICS_SQL)
                .setParameter("language", language)
                .getResultList();

        List<Object[]> matches = new ArrayList<>();
        for (Object[] row : rows) {
            String compGenre = (String) row[5];
            if (!MoviesDataCollectionQueryServiceImpl.genreOverlaps(genreTokens, compGenre)) {
                continue;
            }
            matches.add(new Object[]{row[0], row[1], row[2], row[3], row[4]});
        }
        return matches;
    }
}
