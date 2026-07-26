package com.aura.service.service;

import com.aura.service.dto.BacktestRunStatus;
import com.aura.service.dto.BoxOfficeBacktestResult;
import com.aura.service.dto.BoxOfficeFactorStat;
import com.aura.service.dto.BoxOfficeMovieBaseline;
import com.aura.service.dto.MovieBacktestRow;
import com.aura.service.enums.MovieIndustry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Calls AuraLLM (via {@link LLMService}) once per movie, but only for qualitative 1-5 (or "NA")
 * ratings on each {@link BoxOfficeFactorCatalog} factor - never a dollar figure. Everything from
 * the baseline (B0) through the final predicted gross is computed here or in
 * {@link BoxOfficeBaselineService} from real budget/cast/director/genre/market data, using the
 * LLM's ratings only as the compounding multiplier's per-factor deltas. This replaces an earlier
 * design that asked the LLM for the gross directly as a free-text range - recalibrating that
 * version's stated impact percentages had no measurable effect on accuracy, because the LLM
 * wasn't actually doing arithmetic over them to begin with (see git history). Every movie's result
 * is appended to a log file so the run can be reviewed later; a per-factor rating-frequency summary
 * is appended once the run finishes.
 */
@Slf4j
@Service
public class BoxOfficeBacktestWorkerImpl implements BoxOfficeBacktestWorker {

    private static final String LLM_ERROR_SENTINEL = "Error generating reply from LLM.";

    // A prediction within this fraction of actual gross counts as "within tolerance" - there's no
    // range from the LLM anymore (it only supplies ratings), so this replaces the old
    // predicted-low/predicted-high containment check with a single point-estimate band.
    private static final double TOLERANCE_PCT = 0.25;

    // Factor 46 (Teaser and Trailer Impact) thresholds, per the explicit formula given: released
    // less than 14 days before launch is too late to build hype (-15%); 30-45 days out is the
    // optimal window (+25%). Applied identically to teaser and trailer dates, then averaged.
    private static final int SHORT_WINDOW_DAYS = 14;
    private static final double SHORT_WINDOW_PENALTY = -0.15;
    private static final int OPTIMAL_MIN_DAYS_46 = 30;
    private static final int OPTIMAL_MAX_DAYS_46 = 45;
    private static final double OPTIMAL_BONUS_46 = 0.25;

    // Factor 47 (Timing of First Single Release): catalog description says "6 to 8 weeks before
    // launch" (42-56 days) builds sustained hype; only a positive band is defined; outside it stays
    // neutral (0) rather than inventing a penalty the catalog never specified.
    private static final int OPTIMAL_MIN_DAYS_47 = 42;
    private static final int OPTIMAL_MAX_DAYS_47 = 56;
    private static final double OPTIMAL_BONUS_47 = 0.25;

    // Defensive backstop on the geometric-mean compound multiplier (see geometricCompound()) -
    // should rarely bind given the averaging, but guards against a pathological LLM response.
    private static final double MIN_COMPOUND_MULTIPLIER = 0.3;
    private static final double MAX_COMPOUND_MULTIPLIER = 4.0;

    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final BoxOfficeBaselineService baselineService;
    private final String promptTemplate;

    public BoxOfficeBacktestWorkerImpl(LLMService llmService, ObjectMapper objectMapper,
                                        BoxOfficeBaselineService baselineService) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.baselineService = baselineService;
        this.promptTemplate = loadTemplate();
    }

    @Override
    @Async
    public void processAsync(String runId, BacktestRunStatus status, List<MovieBacktestRow> movies) {
        Path logPath = Path.of(status.getLogFilePath());
        Map<Integer, int[]> tally = new LinkedHashMap<>(); // [ratedCount, naCount], deltaSum tracked separately
        Map<Integer, Double> deltaSumWhenRated = new LinkedHashMap<>();
        try {
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            for (MovieBacktestRow row : movies) {
                BoxOfficeBacktestResult result = processMovie(row);
                status.recordResult(result);
                appendLogLine(logPath, result);
                if (result.error() == null && result.factorDeltas() != null) {
                    tallyFactors(tally, deltaSumWhenRated, result);
                }
            }
            List<BoxOfficeFactorStat> factorStats = buildFactorStats(tally, deltaSumWhenRated);
            appendSummary(logPath, runId, status, factorStats);
            status.complete(factorStats);
            log.info("Box office backtest run {} completed: {} processed, {} validated, {} within tolerance",
                    runId, status.getProcessedCount(), status.getValidatedCount(), status.getWithinRangeCount());
        } catch (Exception e) {
            log.error("Box office backtest run {} failed", runId, e);
            status.fail(e.getMessage());
        }
    }

    private BoxOfficeBacktestResult processMovie(MovieBacktestRow row) {
        String prompt = buildPrompt(row);
        String reply;
        try {
            reply = llmService.generateReply(prompt);
        } catch (Exception e) {
            return errorResult(row, "LLM call threw: " + e.getMessage());
        }
        if (reply == null || reply.isBlank() || reply.equals(LLM_ERROR_SENTINEL)) {
            return errorResult(row, "LLM call failed or returned no content");
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(stripCodeFences(reply));
        } catch (Exception e) {
            return errorResult(row, "Could not parse LLM response as JSON: " + e.getMessage());
        }

        Map<Integer, Integer> ratings = parseFactorRatings(node.path("factorRatings"));
        List<String> postReleaseHelp = arrayToList(node.path("postReleaseFactorsHelp"));
        List<String> postReleaseHurt = arrayToList(node.path("postReleaseFactorsHurt"));
        String rationale = textOrNull(node, "rationale");

        Map<String, Double> factorDeltas = new TreeMap<>((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
        List<Double> ratedDeltas = new ArrayList<>();
        for (BoxOfficeFactorCatalog.FactorDefinition def : BoxOfficeFactorCatalog.byRole(BoxOfficeFactorCatalog.Role.COMPOUNDING)) {
            Integer rating = ratings.get(def.number());
            double delta = (rating == null) ? 0.0 : def.deltaForRating(rating);
            factorDeltas.put(String.valueOf(def.number()), delta);
            if (rating != null) {
                ratedDeltas.add(delta);
            }
        }
        Double delta46 = timingDelta46(row);
        Double delta47 = timingDelta47(row);
        factorDeltas.put("46", delta46 == null ? 0.0 : delta46);
        factorDeltas.put("47", delta47 == null ? 0.0 : delta47);
        if (delta46 != null) {
            ratedDeltas.add(delta46);
        }
        if (delta47 != null) {
            ratedDeltas.add(delta47);
        }

        double compoundMultiplier = geometricCompound(ratedDeltas);

        BoxOfficeMovieBaseline baseline = baselineService.computeBaseline(row);
        double predictedGross = baseline.baselineB0Usd() * compoundMultiplier;

        ActualGross actual = resolveActualGross(row);
        Boolean withinTolerance = null;
        Double deviationPct = null;
        if (actual != null && actual.amountUsd() > 0) {
            deviationPct = Math.abs(predictedGross - actual.amountUsd()) / actual.amountUsd() * 100.0;
            withinTolerance = deviationPct <= TOLERANCE_PCT * 100.0;
        }

        return new BoxOfficeBacktestResult(
                row.movieName(), row.releaseDate(),
                actual == null ? null : actual.amountUsd(),
                actual == null ? null : actual.source(),
                baseline, compoundMultiplier, predictedGross,
                withinTolerance, deviationPct, factorDeltas,
                postReleaseHelp, postReleaseHurt, rationale, null);
    }

    // Geometric mean of the (1+delta) compounding factors, i.e. Y = B0 * exp(mean(ln(1+delta_i))),
    // NOT their raw product. For small deltas, PROD(1+delta_i) ~ exp(SUM(delta_i)) - exponential in
    // the *count* of rated factors, not just their average size. That makes the result depend on
    // how finely the catalog happens to be split (ten near-duplicate +20% factors compound to
    // ~1.2^10 = 6.2x under a raw product, vs. one +20% factor compounding to 1.2x, for what is
    // substantively the same underlying judgment about the movie). The geometric mean is invariant
    // to that split: it converges to a stable multiplier driven by the *average* rated delta, so
    // asking about more factors doesn't itself inflate the prediction. Only non-NA deltas
    // (ratedDeltas) enter the mean; an empty list (every factor came back NA) yields a neutral 1.0.
    private double geometricCompound(List<Double> ratedDeltas) {
        if (ratedDeltas.isEmpty()) {
            return 1.0;
        }
        double logSum = 0.0;
        for (double delta : ratedDeltas) {
            logSum += Math.log(1 + delta);
        }
        double multiplier = Math.exp(logSum / ratedDeltas.size());
        return Math.max(MIN_COMPOUND_MULTIPLIER, Math.min(MAX_COMPOUND_MULTIPLIER, multiplier));
    }

    private BoxOfficeBacktestResult errorResult(MovieBacktestRow row, String error) {
        return new BoxOfficeBacktestResult(
                row.movieName(), row.releaseDate(), null, null, null, null, null, null, null,
                null, List.of(), List.of(), null, error);
    }

    private String buildPrompt(MovieBacktestRow row) {
        String market = MovieIndustry.industryFor(row.language());
        String base = promptTemplate
                .replace("<Movie_Name>", valueOrNA(row.movieName()))
                .replace("<Movie_Release_Date>", valueOrNA(row.releaseDate()))
                .replace("<Movie_Genre>", valueOrNA(row.genre()))
                .replace("<Movie_Language>", valueOrNA(row.language()))
                .replace("<Movie_Release_Day>", valueOrNA(row.releaseDay()))
                .replace("<India_GDP_On_Release_Date>",
                        row.gdpUsdBillions() == null ? "Not Available"
                                : String.format("%.2f Billion USD", row.gdpUsdBillions()))
                .replace("<India_Inflation_Rate_On_Release_Date>",
                        row.inflationRatePct() == null ? "Not Available"
                                : String.format("%.2f%%", row.inflationRatePct()))
                .replace("<Movie_Budget>",
                        row.budget() == null || row.budget() <= 0 ? "Not Available"
                                : String.format("$%,.0f", row.budget()))
                .replace("<Movie_Production_Company>", valueOrNA(row.productionCompanies()))
                .replace("<Movie_Runtime>",
                        row.runtime() == null || row.runtime() <= 0 ? "Not Available"
                                : row.runtime() + " minutes")
                .replace("<Movie_Market_Or_Industry>", market != null ? market : valueOrNA(row.language()))
                .replace("<Movie_Director>", valueOrNA(row.director()))
                .replace("<Movie_Cast>", valueOrNA(row.cast()))
                .replace("<Movie_Synopsis>", valueOrNA(row.synopsis()))
                .replace("<Movie_First_Song_Release_Date>", valueOrNA(row.firstSongReleaseDate()))
                .replace("<Movie_First_Song_Views>",
                        row.firstSongViews() == null ? "Not Available" : String.valueOf(row.firstSongViews()))
                .replace("<Movie_Teaser_Release_Date>", valueOrNA(row.teaserReleaseDate()))
                .replace("<Movie_Teaser_Views>",
                        row.teaserViews() == null ? "Not Available" : String.valueOf(row.teaserViews()))
                .replace("<Movie_Trailer_Release_Date>", valueOrNA(row.trailerReleaseDate()))
                .replace("<Movie_Trailer_Views>",
                        row.trailerViews() == null ? "Not Available" : String.valueOf(row.trailerViews()));

        return base + "\n\n" + buildFactorListSection() + "\n\n" + buildResponseFormatSection();
    }

    private String buildFactorListSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("Factors to rate for this movie:\n");
        for (BoxOfficeFactorCatalog.FactorDefinition def : BoxOfficeFactorCatalog.llmRated()) {
            sb.append("    • ").append(def.number()).append(". ").append(def.name())
                    .append(" [Direction: ").append(directionLabel(def.direction())).append("]")
                    .append(" (Impact if applicable: ").append(impactLabel(def)).append(")");
            if (def.role() == BoxOfficeFactorCatalog.Role.POST_RELEASE) {
                sb.append(" - post-release factor, informational only, not used in the gross calculation");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildResponseFormatSection() {
        List<Integer> numbers = BoxOfficeFactorCatalog.llmRated().stream()
                .map(BoxOfficeFactorCatalog.FactorDefinition::number)
                .collect(Collectors.toList());
        return "For EACH factor number listed above, rate how strongly it applies to THIS movie:\n" +
                "  - 1 = strongly against this movie's performance in that factor's direction\n" +
                "  - 3 = neutral / roughly average\n" +
                "  - 5 = strongly in favor of this movie's performance in that factor's direction\n" +
                "  - \"NA\" = you have no real basis to judge this factor from the data given - do NOT guess a 3 " +
                "just to fill it in, use \"NA\" instead so it is excluded rather than silently treated as average.\n\n" +
                "Respond with ONLY this JSON object, no markdown code fences, no commentary before or after it:\n" +
                "{\n" +
                "  \"factorRatings\": { " + numbers.stream().map(n -> "\"" + n + "\": <1-5 or \"NA\">")
                        .collect(Collectors.joining(", ")) + " },\n" +
                "  \"postReleaseFactorsHelp\": [ \"short strings describing post-release factors (91-100) that " +
                "would help this specific movie\" ],\n" +
                "  \"postReleaseFactorsHurt\": [ \"short strings describing post-release factors (91-100) that " +
                "would hurt this specific movie\" ],\n" +
                "  \"rationale\": \"one or two sentences on the overall qualitative read of this movie\"\n" +
                "}";
    }

    private String directionLabel(BoxOfficeFactorCatalog.Direction direction) {
        return switch (direction) {
            case POSITIVE -> "Positive";
            case NEGATIVE -> "Negative";
            case BIDIRECTIONAL -> "Bidirectional";
        };
    }

    private String impactLabel(BoxOfficeFactorCatalog.FactorDefinition def) {
        double low = def.low() * 100, high = def.high() * 100;
        if (def.direction() == BoxOfficeFactorCatalog.Direction.BIDIRECTIONAL && low == -high) {
            return String.format(Locale.ROOT, "+/- %.0f%%", high);
        }
        return String.format(Locale.ROOT, "%+.0f%% to %+.0f%%", low, high);
    }

    private Map<Integer, Integer> parseFactorRatings(JsonNode ratingsNode) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        if (ratingsNode == null || !ratingsNode.isObject()) {
            return result;
        }
        ratingsNode.fields().forEachRemaining(entry -> {
            Integer factorNumber = parseIntOrNull(entry.getKey());
            if (factorNumber == null) {
                return;
            }
            JsonNode valueNode = entry.getValue();
            Integer rating = asRatingInteger(valueNode);
            if (rating != null && rating >= 1 && rating <= 5) {
                result.put(factorNumber, rating);
            }
            // "NA"/out-of-range/non-numeric values are simply omitted - processMovie() then
            // defaults that factor's delta to 0 (neutral), not the range midpoint.
        });
        return result;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Accepts either a JSON integer or a numeric string, same tolerance ConflictBalanceServiceImpl
    // applies - LLMs are inconsistent about honoring "output a plain integer". A literal "NA" (or
    // anything else non-numeric) correctly falls through to null here.
    private static Integer asRatingInteger(JsonNode valueNode) {
        if (valueNode.isIntegralNumber()) {
            return valueNode.asInt();
        }
        if (valueNode.isTextual()) {
            try {
                return Integer.parseInt(valueNode.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // Averages the teaser and trailer timing deltas (whichever dates are actually present) using
    // the same short-window-penalty / optimal-window-bonus thresholds for both.
    private Double timingDelta46(MovieBacktestRow row) {
        Double trailerDelta = timingDelta(row.trailerReleaseDate(), row.releaseDate(),
                SHORT_WINDOW_DAYS, SHORT_WINDOW_PENALTY, OPTIMAL_MIN_DAYS_46, OPTIMAL_MAX_DAYS_46, OPTIMAL_BONUS_46);
        Double teaserDelta = timingDelta(row.teaserReleaseDate(), row.releaseDate(),
                SHORT_WINDOW_DAYS, SHORT_WINDOW_PENALTY, OPTIMAL_MIN_DAYS_46, OPTIMAL_MAX_DAYS_46, OPTIMAL_BONUS_46);
        if (trailerDelta == null && teaserDelta == null) {
            return null;
        }
        if (trailerDelta == null) {
            return teaserDelta;
        }
        if (teaserDelta == null) {
            return trailerDelta;
        }
        return (trailerDelta + teaserDelta) / 2.0;
    }

    private Double timingDelta47(MovieBacktestRow row) {
        return timingDelta(row.firstSongReleaseDate(), row.releaseDate(),
                -1, 0.0, OPTIMAL_MIN_DAYS_47, OPTIMAL_MAX_DAYS_47, OPTIMAL_BONUS_47);
    }

    // Returns null when either date is missing/unparseable (no basis to compute a timing delta),
    // the short-window penalty when the event lands fewer than shortWindowDays before release
    // (pass a negative shortWindowDays to disable the penalty band entirely, as factor 47 does -
    // its catalog description defines no negative side), the optimal-window bonus when it lands in
    // [optimalMinDays, optimalMaxDays] before release, and 0 (neutral) otherwise.
    private Double timingDelta(String eventDateText, String releaseDateText, int shortWindowDays,
                                double shortWindowPenalty, int optimalMinDays, int optimalMaxDays, double optimalBonus) {
        LocalDate eventDate = parseLocalDate(eventDateText);
        LocalDate releaseDate = parseLocalDate(releaseDateText);
        if (eventDate == null || releaseDate == null) {
            return null;
        }
        long daysBefore = ChronoUnit.DAYS.between(eventDate, releaseDate);
        if (shortWindowDays >= 0 && daysBefore < shortWindowDays) {
            return shortWindowPenalty;
        }
        if (daysBefore >= optimalMinDays && daysBefore <= optimalMaxDays) {
            return optimalBonus;
        }
        return 0.0;
    }

    private LocalDate parseLocalDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private record ActualGross(double amountUsd, String source) {
    }

    private ActualGross resolveActualGross(MovieBacktestRow row) {
        if (row.actualRevenue() != null && row.actualRevenue() > 0) {
            return new ActualGross(row.actualRevenue(), "revenue");
        }
        double indiaPlusOverseas = nz(row.actualIndiaGross()) + nz(row.actualOverseasGross());
        if (indiaPlusOverseas > 0) {
            return new ActualGross(indiaPlusOverseas, "india_gross_collection_usd + overseas_collection_usd");
        }
        double domesticPlusOverseas = nz(row.actualDomesticGross()) + nz(row.actualOverseasGross());
        if (domesticPlusOverseas > 0) {
            return new ActualGross(domesticPlusOverseas, "domestic_collection_usd + overseas_collection_usd");
        }
        if (row.actualFirstDayWorldwide() != null && row.actualFirstDayWorldwide() > 0) {
            return new ActualGross(row.actualFirstDayWorldwide(),
                    "first_day_worldwide_usd (weak proxy - single-day figure, not total gross)");
        }
        return null;
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    private String stripCodeFences(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence != -1) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private List<String> arrayToList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arrayNode) {
            out.add(n.isTextual() ? n.asText() : n.toString());
        }
        return out;
    }

    private static String valueOrNA(String s) {
        return (s == null || s.isBlank()) ? "Not Available" : s.trim();
    }

    private void tallyFactors(Map<Integer, int[]> tally, Map<Integer, Double> deltaSumWhenRated, BoxOfficeBacktestResult result) {
        for (Map.Entry<String, Double> entry : result.factorDeltas().entrySet()) {
            Integer factorNumber = parseIntOrNull(entry.getKey());
            if (factorNumber == null) {
                continue;
            }
            int[] counts = tally.computeIfAbsent(factorNumber, k -> new int[2]);
            if (entry.getValue() != 0.0) {
                counts[0]++; // rated (non-zero delta implies the LLM gave a non-neutral rating, or
                             // it's a server-computed factor that actually applied)
                deltaSumWhenRated.merge(factorNumber, entry.getValue(), Double::sum);
            } else {
                counts[1]++; // NA / neutral
            }
        }
    }

    private List<BoxOfficeFactorStat> buildFactorStats(Map<Integer, int[]> tally, Map<Integer, Double> deltaSumWhenRated) {
        List<BoxOfficeFactorStat> stats = new ArrayList<>();
        for (Map.Entry<Integer, int[]> entry : tally.entrySet()) {
            int factorNumber = entry.getKey();
            int ratedCount = entry.getValue()[0];
            int naCount = entry.getValue()[1];
            double avgDelta = ratedCount == 0 ? 0.0 : deltaSumWhenRated.getOrDefault(factorNumber, 0.0) / ratedCount;
            BoxOfficeFactorCatalog.FactorDefinition def = BoxOfficeFactorCatalog.byNumber(factorNumber);
            String name = def == null ? ("Factor " + factorNumber) : def.name();
            stats.add(new BoxOfficeFactorStat(factorNumber, name, ratedCount, naCount, avgDelta));
        }
        stats.sort((a, b) -> Integer.compare(b.ratedCount(), a.ratedCount()));
        return stats;
    }

    private void appendLogLine(Path logPath, BoxOfficeBacktestResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            Files.writeString(logPath, json + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write backtest log line for {}: {}", result.movieName(), e.getMessage());
        }
    }

    private void appendSummary(Path logPath, String runId, BacktestRunStatus status, List<BoxOfficeFactorStat> factorStats) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SUMMARY runId=").append(runId)
                .append(" completedAt=").append(Instant.now())
                .append(" ===").append(System.lineSeparator());
        sb.append(String.format(
                "Processed %d, validated %d, within %.0f%% tolerance %d (%.1f%% of validated)%n",
                status.getProcessedCount(), status.getValidatedCount(), TOLERANCE_PCT * 100,
                status.getWithinRangeCount(),
                status.getValidatedCount() == 0 ? 0.0
                        : status.getWithinRangeCount() * 100.0 / status.getValidatedCount()));
        sb.append("Per-factor rating frequency (for review - roles/ranges are not auto-adjusted):")
                .append(System.lineSeparator());
        for (BoxOfficeFactorStat stat : factorStats) {
            sb.append(String.format("  %3d. %-55s rated %3d, NA %3d, avg delta when rated %+6.1f%%%n",
                    stat.factorNumber(), stat.factorName(), stat.ratedCount(), stat.naCount(),
                    stat.avgDeltaWhenRated() * 100));
        }
        sb.append("=== END SUMMARY ===").append(System.lineSeparator());
        try {
            Files.writeString(logPath, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write backtest summary for run {}: {}", runId, e.getMessage());
        }
    }

    private static String loadTemplate() {
        try (InputStream in = new ClassPathResource("prompts/box-office-100-factor-prompt.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load box-office-100-factor-prompt.txt", e);
        }
    }
}
