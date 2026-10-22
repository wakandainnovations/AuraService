package com.aura.service.predictor;

import com.aura.service.currency.UsdInrRate;
import com.aura.service.currency.UsdInrRateProvider;
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
 * shifts the assumed audience ratings up from 4/6/8 to 5/7/9. The hit bar is always 2x.
 *
 * <p>AuraPredictor works in USD. An INR {@code estimatedBudget} is converted to USD before the call,
 * and the response comes back as AuraPredictor sent it except that every budget is converted to INR
 * at the current rate, with {@code currency}, {@code usdToInrRate} and {@code exchangeRateDate}
 * added at the top level.
 */
@Service
@RequiredArgsConstructor
public class BudgetRiskService {

    static final double HIT_RATIO = 2.0;
    static final List<Double> DEFAULT_RATINGS = List.of(4.0, 6.0, 8.0);
    static final List<Double> STAR_LEAD_RATINGS = List.of(5.0, 7.0, 9.0);
    static final int STAR_LEAD_RATING_RANGE = 10_000;
    private static final List<String> BUDGET_FIELDS = List.of("maxBudget", "safeBudget", "plannedBudget");

    private final AuraPredictorClient auraPredictorClient;
    private final LeadActorRatingRangeLookup leadActorRatingRangeLookup;
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
        Integer leadRatingRange = leadActor == null
                ? null : leadActorRatingRangeLookup.highestRecentRatingRange(leadActor, request.releaseDate());
        if (leadRatingRange != null && leadRatingRange > ratingRange) {
            ratingRange = leadRatingRange;
        }
        List<Double> ratings = leadRatingRange != null && leadRatingRange >= STAR_LEAD_RATING_RANGE
                ? STAR_LEAD_RATINGS : DEFAULT_RATINGS;

        Double budgetUsd = request.estimatedBudget();
        if (budgetUsd != null && request.currency() != BudgetCurrency.USD) {
            budgetUsd = rate.inrToUsd(budgetUsd);
        }

        return new BudgetRiskUpstreamRequest(
                request.movieName(),
                request.releaseDate(),
                request.language(),
                budgetUsd,
                HIT_RATIO,
                request.genre(),
                request.cbfcRating(),
                request.isRemake(),
                ratingRange,
                request.productionCompany(),
                leadActor,
                request.supportingActors(),
                request.director(),
                request.musicDirector(),
                ratings);
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
