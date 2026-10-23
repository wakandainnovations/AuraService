package com.aura.service.predictor;

import com.aura.service.predictor.RecentRatingRangeLookup.Role;

import java.util.List;

/** People credited on films of a similar budget in {@code india_movies_data_collection}. */
public interface SimilarBudgetTalentLookup {

    /** A person's best-earning film in the budget band. Money is in USD, as stored. */
    record SimilarBudgetFilm(String name, String movieName, String releaseDate, String language,
                             double budget, double revenue) {
    }

    /**
     * One film per person credited in {@code role} on a film with a budget from {@code minBudget}
     * to {@code maxBudget} (USD) and some revenue, released from {@code fromDate} up to before
     * {@code beforeDate} (YYYY-MM-DD): the one with the highest revenue / budget. Best ratio first,
     * at most {@code limit}. With a {@code homeLanguage}, only people most of whose films in that
     * role are originally in it; null means any language.
     */
    List<SimilarBudgetFilm> bestSimilarBudgetFilms(Role role, double minBudget, double maxBudget, String homeLanguage,
                                                   String fromDate, String beforeDate, int limit);
}
