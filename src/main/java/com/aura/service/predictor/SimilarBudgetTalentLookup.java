package com.aura.service.predictor;

import com.aura.service.predictor.RecentRatingRangeLookup.Role;

import java.util.List;

/** People credited on films of a similar budget in {@code india_movies_data_collection}. */
public interface SimilarBudgetTalentLookup {

    /** A person's best-earning recent hit in the budget band. Money is in USD, as stored. */
    record SimilarBudgetFilm(String name, String movieName, String releaseDate, String language,
                             double budget, double revenue) {
    }

    /**
     * One film per person credited in {@code role} on a film with a budget from {@code minBudget}
     * to {@code maxBudget} (USD) that earned at least {@code minRevenueRatio} times its budget: the
     * one with the highest revenue / budget among
     * their last 3 films released before {@code beforeDate} (YYYY-MM-DD) and any released from
     * {@code recentSince} on. Best ratio first, at most {@code limit}.
     *
     * <p>Only people who are from {@code language} - most of their films in the role are originally
     * in it - qualify. With {@code panIndia}, so do people who've had a film released in it.
     */
    List<SimilarBudgetFilm> bestSimilarBudgetFilms(Role role, double minBudget, double maxBudget,
                                                   double minRevenueRatio, String language, boolean panIndia,
                                                   String recentSince, String beforeDate, int limit);
}
