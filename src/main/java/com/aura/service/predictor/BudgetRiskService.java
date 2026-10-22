package com.aura.service.predictor;

import com.aura.service.currency.UsdInrRate;
import com.aura.service.currency.UsdInrRateProvider;
import com.aura.service.predictor.RecentRatingRangeLookup.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Budget-risk check for a planned movie via AuraPredictor. Inject this from other services; {@link
 * BudgetRiskController} exposes it over HTTP.
 *
 * <p>{@code ratingRange} is the marketing-budget band, raised to the lead actor's highest rating
 * range among their last 3 films when that is higher. A lead with a 10000+ film among those 3 also
 * shifts the assumed audience ratings up from 4/6/8 to 5/7/9.
 *
 * <p>{@code hitRatio}, the revenue multiple of the budget that counts as a hit, falls in a straight
 * line from 2x at a $1M budget to 1.35x at $35M (flat beyond either end; 2x when no budget is
 * given). Proven talent lowers it further, by up to 35%: each of the lead actor, director and music
 * director whose last 3 films include a 10000+ rating range counts fully, 1000+ counts half, and
 * the lead weighs 50%, the director 30% and the music director 20%. It never goes below 1x, the
 * lowest AuraPredictor accepts.
 *
 * <p>AuraPredictor works in USD. An INR {@code estimatedBudget} is converted to USD before the call,
 * and the response comes back as AuraPredictor sent it except that every budget is converted to INR
 * at the current rate, with {@code currency}, {@code usdToInrRate} and {@code exchangeRateDate}
 * added at the top level.
 */
@Service
@RequiredArgsConstructor
public class BudgetRiskService {

    static final double SMALL_BUDGET_USD = 1_000_000;
    static final double SMALL_BUDGET_HIT_RATIO = 2.0;
    static final double LARGE_BUDGET_USD = 35_000_000;
    static final double LARGE_BUDGET_HIT_RATIO = 1.35;
    static final double MAX_TALENT_CUT = 0.35;
    static final double MIN_HIT_RATIO = 1.0;
    static final double LEAD_WEIGHT = 0.5;
    static final double DIRECTOR_WEIGHT = 0.3;
    static final double MUSIC_DIRECTOR_WEIGHT = 0.2;
    static final int PROVEN_RATING_RANGE = 1_000;
    static final List<Double> DEFAULT_RATINGS = List.of(4.0, 6.0, 8.0);
    static final List<Double> STAR_LEAD_RATINGS = List.of(5.0, 7.0, 9.0);
    static final int STAR_LEAD_RATING_RANGE = 10_000;
    private static final List<String> BUDGET_FIELDS = List.of("maxBudget", "safeBudget", "plannedBudget");

    private final AuraPredictorClient auraPredictorClient;
    private final RecentRatingRangeLookup recentRatingRangeLookup;
    private final UsdInrRateProvider usdInrRateProvider;

    public JsonNode assess(BudgetRiskRequest request) {
        UsdInrRate rate = usdInrRateProvider.currentRate();
        JsonNode response = auraPredictorClient.assessBudgetRisk(toUpstream(request, rate));
        return toInr(response, rate);
    }

    BudgetRiskUpstreamRequest toUpstream(BudgetRiskRequest request, UsdInrRate rate) {
        MarketingBudget marketingBudget = request.marketingBudget() == null
                ? MarketingBudget.MEDIUM : request.marketingBudget();
        int ratingRange = marketingBudget.ratingRange(isHindi(request.language()));

        String leadActor = blankToNull(request.leadActor());
        String director = blankToNull(request.director());
        String musicDirector = blankToNull(request.musicDirector());
        Integer leadRatingRange = recentRatingRange(Role.LEAD_ACTOR, leadActor, request.releaseDate());
        if (leadRatingRange != null && leadRatingRange > ratingRange) {
            ratingRange = leadRatingRange;
        }
        List<Double> ratings = leadRatingRange != null && leadRatingRange >= STAR_LEAD_RATING_RANGE
                ? STAR_LEAD_RATINGS : DEFAULT_RATINGS;

        Double budgetUsd = request.estimatedBudget();
        if (budgetUsd != null && request.currency() != BudgetCurrency.USD) {
            budgetUsd = rate.inrToUsd(budgetUsd);
        }

        double talentScore = LEAD_WEIGHT * talentCredit(leadRatingRange)
                + DIRECTOR_WEIGHT * talentCredit(recentRatingRange(Role.DIRECTOR, director, request.releaseDate()))
                + MUSIC_DIRECTOR_WEIGHT * talentCredit(recentRatingRange(Role.MUSIC_DIRECTOR, musicDirector, request.releaseDate()));
        double hitRatio = Math.max(MIN_HIT_RATIO,
                Math.round(budgetHitRatio(budgetUsd) * (1 - MAX_TALENT_CUT * talentScore) * 100) / 100.0);

        return new BudgetRiskUpstreamRequest(
                request.movieName(),
                request.releaseDate(),
                request.language(),
                budgetUsd,
                hitRatio,
                request.genre(),
                request.cbfcRating(),
                request.isRemake(),
                ratingRange,
                request.productionCompany(),
                leadActor,
                request.supportingActors(),
                director,
                musicDirector,
                ratings);
    }

    private Integer recentRatingRange(Role role, String name, String releaseDate) {
        return name == null ? null : recentRatingRangeLookup.highestRecentRatingRange(role, name, releaseDate);
    }

    static double budgetHitRatio(Double budgetUsd) {
        if (budgetUsd == null || budgetUsd <= SMALL_BUDGET_USD) {
            return SMALL_BUDGET_HIT_RATIO;
        }
        if (budgetUsd >= LARGE_BUDGET_USD) {
            return LARGE_BUDGET_HIT_RATIO;
        }
        return SMALL_BUDGET_HIT_RATIO - (SMALL_BUDGET_HIT_RATIO - LARGE_BUDGET_HIT_RATIO)
                * (budgetUsd - SMALL_BUDGET_USD) / (LARGE_BUDGET_USD - SMALL_BUDGET_USD);
    }

    /** 1 for a 10000+ recent rating range, 0.5 for 1000+, else 0. */
    private static double talentCredit(Integer ratingRange) {
        if (ratingRange == null || ratingRange < PROVEN_RATING_RANGE) {
            return 0;
        }
        return ratingRange >= STAR_LEAD_RATING_RANGE ? 1 : 0.5;
    }

    private static JsonNode toInr(JsonNode response, UsdInrRate rate) {
        if (!(response instanceof ObjectNode root)) {
            return response;
        }
        convertAssessments(root.path("assessments"), rate);
        convertAssessments(root.path("breakout").path("assessments"), rate);
        root.put("currency", "INR");
        root.put("usdToInrRate", rate.usdToInrRate());
        root.put("exchangeRateDate", rate.rateDate().toString());
        return root;
    }

    // Budgets round to whole rupees; null (risky at any budget) stays null.
    private static void convertAssessments(JsonNode assessments, UsdInrRate rate) {
        for (JsonNode assessment : assessments) {
            if (assessment instanceof ObjectNode node) {
                for (String field : BUDGET_FIELDS) {
                    JsonNode value = node.get(field);
                    if (value != null && value.isNumber()) {
                        node.put(field, (double) Math.round(rate.usdToInr(value.asDouble())));
                    }
                }
            }
        }
    }

    /** AuraPredictor takes the original language first, e.g. "hindi" or "hindi, tamil". */
    private static boolean isHindi(String language) {
        return language.split(",")[0].trim().toLowerCase(Locale.ROOT).equals("hindi");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
