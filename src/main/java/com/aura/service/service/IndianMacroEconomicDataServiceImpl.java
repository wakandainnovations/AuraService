package com.aura.service.service;

import com.aura.service.dto.IndianMacroSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code movies_data_collection} has no JPA entity of its own (it's a bulk-loaded reference
 * dataset, not app-managed data), so this reads it directly via a native query rather than
 * through a Spring Data repository. GDP/inflation are stored per-movie-row rather than in a
 * dedicated macro table, but are consistent across rows for the same country/year (confirmed
 * against live data — see {@code docs/PREDICTIVE_FACTOR_DATA_AUDIT.md}), so the most frequent
 * (gdp, inflation) pair for India in the release year is the year's true value; taking the mode
 * rather than an arbitrary row also rides out the handful of mis-tagged rows that carry another
 * country's figures.
 */
@Service
public class IndianMacroEconomicDataServiceImpl implements IndianMacroEconomicDataService {

    private static final String LOOKUP_SQL =
            "SELECT gdp_usd_billions, inflation_rate_pct FROM movies_data_collection " +
            "WHERE country = 'India' AND release_date LIKE CONCAT(CAST(:year AS text), '-%') " +
            "AND gdp_usd_billions IS NOT NULL AND gdp_usd_billions <> 0 " +
            "GROUP BY gdp_usd_billions, inflation_rate_pct " +
            "ORDER BY COUNT(*) DESC LIMIT 1";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public IndianMacroSnapshot lookup(LocalDate releaseDate) {
        if (releaseDate == null) {
            return null;
        }

        List<Object[]> rows = entityManager.createNativeQuery(LOOKUP_SQL)
                .setParameter("year", String.valueOf(releaseDate.getYear()))
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }

        Object[] row = rows.get(0);
        return new IndianMacroSnapshot(toDouble(row[0]), toDouble(row[1]));
    }

    private Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
