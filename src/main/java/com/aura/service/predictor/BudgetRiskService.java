package com.aura.service.predictor;

import com.aura.service.currency.UsdInrRate;
import com.aura.service.currency.UsdInrRateProvider;
import com.aura.service.predictor.RecentRatingRangeLookup.Role;
import com.aura.service.predictor.SimilarBudgetTalentLookup.SimilarBudgetFilm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 *
 * <p>With a budget, {@code alternatives} lists other lead actors and music directors who'd make
 * the movie likelier to be a hit. Candidates are people credited in that role on a film with half
 * to twice the budget (USD) that earned at least twice its budget and is one of their last 3
 * released films or came out in the past year, best revenue / budget first. They must be from the
 * movie's original language (the first listed): for a lead actor, most of their lead films are
 * originally in it; a music director need only have worked in it once, since many work across
 * industries. A movie in several languages is taken as pan-India, and people who've had a film
 * released in its original language qualify too, so they have an audience there. The top 8 per
 * role are scored as if the request had named them instead, with every rule above re-applied, and
 * up to 5 whose average hit probability across the assessments beats the request's are returned,
 * best first.
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
    static final double SIMILAR_BUDGET_MIN_FACTOR = 0.5;
    static final double SIMILAR_BUDGET_MAX_FACTOR = 2.0;
    static final double SIMILAR_FILM_MIN_REVENUE_RATIO = 2.0;
    static final int RECENT_FILM_YEARS = 1;
    static final int CANDIDATES_SCORED = 8;
    static final int MAX_ALTERNATIVES = 5;
    static final String PAN_INDIA = "pan-india";
    private static final List<String> BUDGET_FIELDS = List.of("maxBudget", "safeBudget", "plannedBudget");
    private static final Logger log = LoggerFactory.getLogger(BudgetRiskService.class);

    private final AuraPredictorClient auraPredictorClient;
    private final RecentRatingRangeLookup recentRatingRangeLookup;
    private final UsdInrRateProvider usdInrRateProvider;
    private final SimilarBudgetTalentLookup similarBudgetTalentLookup;

    public JsonNode assess(BudgetRiskRequest request) {
        UsdInrRate rate = usdInrRateProvider.currentRate();
        BudgetRiskUpstreamRequest upstream = toUpstream(request, rate);
        JsonNode response = auraPredictorClient.assessBudgetRisk(upstream);
        OptionalDouble hitProbability = averageHitProbability(response);
        if (upstream.budget() != null && hitProbability.isPresent() && response instanceof ObjectNode root) {
            root.set("alternatives", alternatives(request, upstream.budget(), hitProbability.getAsDouble(), rate));
        }
        return toInr(response, rate);
    }

    private ObjectNode alternatives(BudgetRiskRequest request, double budgetUsd, double hitProbability,
                                    UsdInrRate rate) {
        List<String> languages = languages(request.language());
        Audience audience = new Audience(languages.isEmpty() ? "" : languages.get(0), languages.size() > 1);
        Set<String> cast = Stream.concat(Stream.ofNullable(request.leadActor()),
                        request.supportingActors() == null ? Stream.empty() : request.supportingActors().stream())
                .map(BudgetRiskService::nameKey)
                .collect(Collectors.toSet());
        Set<String> musicDirector = Stream.ofNullable(request.musicDirector())
                .map(BudgetRiskService::nameKey)
                .collect(Collectors.toSet());

        ObjectNode alternatives = JsonNodeFactory.instance.objectNode();
        alternatives.put("averageHitProbability", hitProbability);
        alternatives.put("languageScope", audience.panIndia() ? PAN_INDIA : audience.language());
        alternatives.put("originalLanguage", audience.language());
        alternatives.set("leadActors", betterTalent(Role.LEAD_ACTOR, request, budgetUsd, audience, cast,
                hitProbability, request::withLeadActor, rate));
        alternatives.set("musicDirectors", betterTalent(Role.MUSIC_DIRECTOR, request, budgetUsd, audience,
                musicDirector, hitProbability, request::withMusicDirector, rate));
        return alternatives;
    }

    /** The movie's original language, and whether it's pan-India (in several languages). */
    private record Audience(String language, boolean panIndia) {
    }

    /** Candidates in {@code role} that beat {@code hitProbability} when swapped into the request. */
    private ArrayNode betterTalent(Role role, BudgetRiskRequest request, double budgetUsd, Audience audience,
                                   Set<String> excluded, double hitProbability,
                                   Function<String, BudgetRiskRequest> swap, UsdInrRate rate) {
        // Released means out by today, and before the planned release.
        LocalDate today = LocalDate.now();
        String releasedBefore = LocalDate.parse(request.releaseDate()).isAfter(today)
                ? today.plusDays(1).toString() : request.releaseDate();
        List<SimilarBudgetFilm> candidates = similarBudgetTalentLookup.bestSimilarBudgetFilms(role,
                        budgetUsd * SIMILAR_BUDGET_MIN_FACTOR, budgetUsd * SIMILAR_BUDGET_MAX_FACTOR,
                        SIMILAR_FILM_MIN_REVENUE_RATIO, audience.language(), audience.panIndia(),
                        today.minusYears(RECENT_FILM_YEARS).toString(), releasedBefore, CANDIDATES_SCORED + excluded.size())
                .stream()
                .filter(film -> !excluded.contains(nameKey(film.name())))
                .limit(CANDIDATES_SCORED)
                .toList();

        List<ObjectNode> better = new ArrayList<>();
        for (SimilarBudgetFilm film : candidates) {
            BudgetRiskUpstreamRequest upstream = toUpstream(swap.apply(film.name()), rate);
            JsonNode response;
            try {
                response = auraPredictorClient.assessBudgetRisk(upstream);
            } catch (AuraPredictorException ex) {
                log.warn("budget-risk alternative skipped role={} name={} cause={}", role, film.name(), ex.getMessage());
                continue;
            }
            OptionalDouble candidateHitProbability = averageHitProbability(response);
            if (candidateHitProbability.isPresent() && candidateHitProbability.getAsDouble() > hitProbability) {
                better.add(alternative(film, upstream, response, candidateHitProbability.getAsDouble(),
                        hitProbability, rate));
            }
        }

        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        better.stream()
                .sorted(Comparator.comparingDouble((ObjectNode node) -> node.path("averageHitProbability").asDouble())
                        .reversed())
                .limit(MAX_ALTERNATIVES)
                .forEach(result::add);
        return result;
    }

    private static ObjectNode alternative(SimilarBudgetFilm film, BudgetRiskUpstreamRequest upstream, JsonNode response,
                                          double hitProbability, double currentHitProbability, UsdInrRate rate) {
        ObjectNode similarBudgetFilm = JsonNodeFactory.instance.objectNode();
        similarBudgetFilm.put("movieName", film.movieName());
        similarBudgetFilm.put("releaseDate", film.releaseDate());
        similarBudgetFilm.put("language", film.language());
        similarBudgetFilm.put("budget", (double) Math.round(rate.usdToInr(film.budget())));
        similarBudgetFilm.put("revenue", (double) Math.round(rate.usdToInr(film.revenue())));

        JsonNode assessments = response.path("assessments");
        convertAssessments(assessments, rate);

        ObjectNode alternative = JsonNodeFactory.instance.objectNode();
        alternative.put("name", film.name());
        alternative.put("averageHitProbability", hitProbability);
        alternative.put("hitProbabilityGain", hitProbability - currentHitProbability);
        alternative.put("ratingRange", upstream.ratingRange());
        alternative.put("hitRatio", upstream.hitRatio());
        alternative.set("similarBudgetFilm", similarBudgetFilm);
        alternative.set("assessments", assessments);
        return alternative;
    }

    /** Mean {@code hitProbability} over the main assessments; empty when there's none (no budget sent). */
    private static OptionalDouble averageHitProbability(JsonNode response) {
        List<Double> probabilities = new ArrayList<>();
        for (JsonNode assessment : response.path("assessments")) {
            JsonNode value = assessment.get("hitProbability");
            if (value != null && value.isNumber()) {
                probabilities.add(value.asDouble());
            }
        }
        return probabilities.stream().mapToDouble(Double::doubleValue).average();
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

    /** The movie's languages, lower-cased, original first. */
    private static List<String> languages(String language) {
        return Stream.of(language.split(","))
                .map(part -> part.trim().toLowerCase(Locale.ROOT))
                .filter(part -> !part.isEmpty())
                .distinct()
                .toList();
    }

    private static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /** AuraPredictor takes the original language first, e.g. "hindi" or "hindi, tamil". */
    private static boolean isHindi(String language) {
        return language.split(",")[0].trim().toLowerCase(Locale.ROOT).equals("hindi");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
