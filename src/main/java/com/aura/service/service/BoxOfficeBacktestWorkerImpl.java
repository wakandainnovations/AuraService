package com.aura.service.service;

import com.aura.service.dto.BacktestRunStatus;
import com.aura.service.dto.BoxOfficeBacktestResult;
import com.aura.service.dto.BoxOfficeFactorStat;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Calls AuraLLM (via {@link LLMService}) once per movie with the 100+3-factor box-office
 * prediction prompt (template at {@code prompts/box-office-100-factor-prompt.txt}), then checks
 * the "Projected Global Gross" prediction against what {@code movies_data_collection} recorded
 * actually happened. Every movie's result is appended to a log file so the run can be reviewed
 * later; a per-factor citation-vs-outcome summary is appended once the run finishes. This never
 * rewrites the prompt's stated impact ranges itself — see the class javadoc on
 * {@link BoxOfficeBacktestService} for why that's a deliberate scope boundary.
 */
@Slf4j
@Service
public class BoxOfficeBacktestWorkerImpl implements BoxOfficeBacktestWorker {

    private static final String LLM_ERROR_SENTINEL = "Error generating reply from LLM.";

    // Matches an LLM-cited factor string like "61. Holiday Release Windows" or
    // "Factor 61: Holiday Release Windows" down to a canonical "61. Holiday Release Windows" key,
    // so citations of the same factor group together in the summary even if the model varies its
    // phrasing slightly. Falls back to the raw trimmed text when no leading number is present.
    private static final Pattern FACTOR_PREFIX =
            Pattern.compile("^(?:factor\\s*)?#?\\s*(\\d{1,3})\\s*[.:\\-)]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    // "$9M – $12M", "$90 Million – $130 Million USD", "$1.2B - $1.5B" — pulls the first two
    // dollar amounts out of a free-text gross-range string. A bare number with no unit is assumed
    // to already be an absolute USD figure.
    private static final Pattern MONEY_PATTERN =
            Pattern.compile("\\$?\\s*([0-9][0-9,]*\\.?[0-9]*)\\s*(billion|million|bn|mn|b|m)?\\b",
                    Pattern.CASE_INSENSITIVE);

    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final String promptTemplate;

    public BoxOfficeBacktestWorkerImpl(LLMService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.promptTemplate = loadTemplate();
    }

    @Override
    @Async
    public void processAsync(String runId, BacktestRunStatus status, List<MovieBacktestRow> movies) {
        Path logPath = Path.of(status.getLogFilePath());
        Map<String, int[]> tally = new LinkedHashMap<>();
        try {
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            for (MovieBacktestRow row : movies) {
                BoxOfficeBacktestResult result = processMovie(row);
                status.recordResult(result);
                appendLogLine(logPath, result);
                if (result.error() == null && result.predictedLowUsd() != null && result.actualGrossUsd() != null) {
                    tallyFactors(tally, result);
                }
            }
            List<BoxOfficeFactorStat> factorStats = buildFactorStats(tally);
            appendSummary(logPath, runId, status, factorStats);
            status.complete(factorStats);
            log.info("Box office backtest run {} completed: {} processed, {} validated, {} within predicted range",
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

        String projectedGrossRaw = textOrNull(node, "Projected Global Gross");
        double[] range = parseGrossRange(projectedGrossRaw);
        List<String> upside = arrayToList(node.path("The Upside Multipliers (Revenue Boosters)"));
        List<String> downside = arrayToList(node.path("The Downside Multipliers (Revenue Leakage)"));
        String verdict = textOrNull(node, "Final Estimated Verdict");

        ActualGross actual = resolveActualGross(row);

        Double predictedLow = range == null ? null : range[0];
        Double predictedHigh = range == null ? null : range[1];
        Boolean withinRange = null;
        Double deviationPct = null;
        if (range != null && actual != null) {
            withinRange = actual.amountUsd() >= predictedLow && actual.amountUsd() <= predictedHigh;
            if (actual.amountUsd() < predictedLow) {
                deviationPct = (predictedLow - actual.amountUsd()) / predictedLow * 100.0;
            } else if (actual.amountUsd() > predictedHigh) {
                deviationPct = (actual.amountUsd() - predictedHigh) / predictedHigh * 100.0;
            } else {
                deviationPct = 0.0;
            }
        }

        return new BoxOfficeBacktestResult(
                row.movieName(), row.releaseDate(),
                actual == null ? null : actual.amountUsd(),
                actual == null ? null : actual.source(),
                predictedLow, predictedHigh, projectedGrossRaw,
                withinRange, deviationPct, upside, downside, verdict, null);
    }

    private BoxOfficeBacktestResult errorResult(MovieBacktestRow row, String error) {
        return new BoxOfficeBacktestResult(
                row.movieName(), row.releaseDate(), null, null, null, null, null, null, null,
                List.of(), List.of(), null, error);
    }

    private String buildPrompt(MovieBacktestRow row) {
        String market = MovieIndustry.industryFor(row.language());
        return promptTemplate
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
    }

    private record ActualGross(double amountUsd, String source) {
    }

    // Precedence follows what's actually populated in movies_data_collection for Indian rows
    // (per docs/PREDICTIVE_FACTOR_DATA_AUDIT.md): global `revenue` is the most standard figure and
    // maps most directly onto "Projected Global Gross"; the India/overseas split and domestic/
    // overseas split are reasonable substitutes when revenue is zero; first_day_worldwide is kept
    // only as a last-resort proxy since it's a single day, not a total gross.
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

    private double[] parseGrossRange(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = MONEY_PATTERN.matcher(raw);
        List<Double> values = new ArrayList<>();
        while (m.find() && values.size() < 2) {
            if (m.group(1) == null) {
                continue;
            }
            double number;
            try {
                number = Double.parseDouble(m.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            String unit = m.group(2) == null ? "" : m.group(2).toLowerCase();
            double usd = switch (unit) {
                case "billion", "bn", "b" -> number * 1_000_000_000d;
                case "million", "mn", "m" -> number * 1_000_000d;
                default -> number;
            };
            values.add(usd);
        }
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() == 1) {
            return new double[]{values.get(0), values.get(0)};
        }
        return new double[]{Math.min(values.get(0), values.get(1)), Math.max(values.get(0), values.get(1))};
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

    private String normalizeFactorLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher m = FACTOR_PREFIX.matcher(trimmed);
        if (m.matches() && !m.group(2).isBlank()) {
            return m.group(1) + ". " + m.group(2).trim();
        }
        return trimmed;
    }

    // tally[factor] = [citedAsUpside, upsideConfirmed, citedAsDownside, downsideConfirmed]. See
    // BoxOfficeFactorStat's javadoc for what "confirmed" means here — an outcome-direction check,
    // not a causal isolation of that one factor.
    private void tallyFactors(Map<String, int[]> tally, BoxOfficeBacktestResult result) {
        boolean metLow = result.actualGrossUsd() >= result.predictedLowUsd();
        boolean underHigh = result.actualGrossUsd() <= result.predictedHighUsd();
        for (String raw : result.upsideFactorsCited()) {
            String key = normalizeFactorLabel(raw);
            if (key == null) {
                continue;
            }
            int[] counts = tally.computeIfAbsent(key, k -> new int[4]);
            counts[0]++;
            if (metLow) {
                counts[1]++;
            }
        }
        for (String raw : result.downsideFactorsCited()) {
            String key = normalizeFactorLabel(raw);
            if (key == null) {
                continue;
            }
            int[] counts = tally.computeIfAbsent(key, k -> new int[4]);
            counts[2]++;
            if (underHigh) {
                counts[3]++;
            }
        }
    }

    private List<BoxOfficeFactorStat> buildFactorStats(Map<String, int[]> tally) {
        return tally.entrySet().stream()
                .map(e -> new BoxOfficeFactorStat(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .sorted((a, b) -> Integer.compare(
                        b.citedAsUpsideCount() + b.citedAsDownsideCount(),
                        a.citedAsUpsideCount() + a.citedAsDownsideCount()))
                .collect(Collectors.toList());
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

    // Written once, at the end of the run: per-factor citation counts vs. how often the outcome
    // actually matched the cited direction. This is the "average impact score" report a human can
    // use to decide whether a factor's stated range in the prompt catalog needs adjusting — no
    // range is changed automatically.
    private void appendSummary(Path logPath, String runId, BacktestRunStatus status, List<BoxOfficeFactorStat> factorStats) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SUMMARY runId=").append(runId)
                .append(" completedAt=").append(Instant.now())
                .append(" ===").append(System.lineSeparator());
        sb.append(String.format(
                "Processed %d, validated %d, within predicted range %d (%.1f%% of validated)%n",
                status.getProcessedCount(), status.getValidatedCount(), status.getWithinRangeCount(),
                status.getValidatedCount() == 0 ? 0.0
                        : status.getWithinRangeCount() * 100.0 / status.getValidatedCount()));
        sb.append("Per-factor citation vs. outcome (for review - impact ranges are not auto-adjusted):")
                .append(System.lineSeparator());
        for (BoxOfficeFactorStat stat : factorStats) {
            if (stat.citedAsUpsideCount() > 0) {
                sb.append(String.format("  [UPSIDE]   %-70s cited %3d, confirmed %3d (%.0f%%)%n",
                        stat.factor(), stat.citedAsUpsideCount(), stat.upsideConfirmedCount(),
                        stat.upsideConfirmationRate() * 100));
            }
            if (stat.citedAsDownsideCount() > 0) {
                sb.append(String.format("  [DOWNSIDE] %-70s cited %3d, confirmed %3d (%.0f%%)%n",
                        stat.factor(), stat.citedAsDownsideCount(), stat.downsideConfirmedCount(),
                        stat.downsideConfirmationRate() * 100));
            }
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
