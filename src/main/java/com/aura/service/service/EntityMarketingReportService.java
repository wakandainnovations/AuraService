package com.aura.service.service;

import com.aura.service.dto.CompetitorSnapshot;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityMarketingReportResponse.CompetitivePositioning;
import com.aura.service.dto.EntityMarketingReportResponse.HeadlineMetrics;
import com.aura.service.dto.EntityStatsAvgResponse;
import com.aura.service.dto.EntityStatsResponse;
import com.aura.service.dto.SentimentOverTimeResponse;
import com.aura.service.enums.TimePeriod;
import com.aura.service.proxy.AuraMathProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assembles the complete, prospect-facing {@link EntityMarketingReportResponse} for a single
 * managed entity by combining this service's own analytics ({@link DashboardService} /
 * {@link EntityService}) with the upstream AuraMath entity-report
 * ({@code GET /api/marketing/entity-report/{entityId}}).
 *
 * <p>The entity profile and headline metrics are mandatory. The entity is loaded via
 * {@link EntityService#getEntityById}, which is owner-scoped: an entity that is missing <em>or</em>
 * owned by another user surfaces as {@code 404}. Every other section is optional and degrades to
 * {@code null} on failure so a single flaky downstream never blocks a report being shown to a
 * prospect.
 */
@Slf4j
@Service
public class EntityMarketingReportService {

    private static final String WRAPPER_PATH = "/api/entities/{entityType}/{id}/marketing-report";

    private final EntityService entityService;
    private final DashboardService dashboardService;
    private final AuraMathProxyService auraMathProxy;
    private final ObjectMapper objectMapper;

    public EntityMarketingReportService(EntityService entityService,
                                        DashboardService dashboardService,
                                        AuraMathProxyService auraMathProxy,
                                        ObjectMapper objectMapper) {
        this.entityService = entityService;
        this.dashboardService = dashboardService;
        this.auraMathProxy = auraMathProxy;
        this.objectMapper = objectMapper;
    }

    public EntityMarketingReportResponse generateReport(String entityType, Long id,
                                                        TimePeriod period, int windowDays) {
        // Mandatory and owner-scoped: 404s if the entity is absent or not owned by the caller,
        // 400s on a type mismatch — this is what enforces ownership for the whole report.
        EntityDetailResponse entity = entityService.getEntityById(entityType, id);

        // Mandatory: the headline numbers the whole report is built around.
        EntityStatsResponse stats = dashboardService.getEntityStats(id);
        EntityStatsAvgResponse avg = dashboardService.getEntityStatsAvg(id);

        Map<String, Map<String, Long>> platformReach = optional("platform-reach", id,
                () -> dashboardService.getPlatformMentions(id));

        HeadlineMetrics headline = HeadlineMetrics.builder()
                .totalMentions(stats.getTotalMentions())
                .overallSentiment(stats.getOverallSentiment())
                .positivityRatio(avg.getPositiveRatio())
                .positiveSentiment(stats.getPositiveSentiment())
                .negativeSentiment(stats.getNegativeSentiment())
                .neutralSentiment(stats.getNeutralSentiment())
                .netSentimentScore(stats.getNetSentimentScore())
                .platformsCovered(platformReach == null ? 0 : platformReach.size())
                .build();

        CompetitivePositioning positioning = optional("competitive-positioning", id,
                () -> buildPositioning(entity.getName(), dashboardService.getCompetitorSnapshot(id)));

        SentimentOverTimeResponse trend = optional("sentiment-trend", id,
                () -> dashboardService.getSentimentOverTime(period, List.of(id)));

        var definingMoments = optional("defining-moments", id,
                () -> dashboardService.getCheckpointImpact(id, windowDays));

        AuraMathResult auraMath = fetchAuraMathReport(id);

        List<String> highlights = buildHighlights(entity, headline, positioning, platformReach);

        return EntityMarketingReportResponse.builder()
                .generatedAt(Instant.now())
                .period(period.name())
                .entity(entity)
                .headlineMetrics(headline)
                .competitivePositioning(positioning)
                .sentimentTrend(trend)
                .platformReach(platformReach)
                .definingMoments(definingMoments)
                .auraMathIntelligence(auraMath.body())
                .auraMathStatus(auraMath.status())
                .highlights(highlights)
                .build();
    }

    // ------------------------------------------------------------------
    // Competitive positioning
    // ------------------------------------------------------------------

    private CompetitivePositioning buildPositioning(String entityName, List<CompetitorSnapshot> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        // Rank by net sentiment (highest first); the snapshot already contains the entity + competitors.
        List<CompetitorSnapshot> ranked = new ArrayList<>(snapshot);
        ranked.sort(Comparator.comparingDouble(CompetitorSnapshot::getNetSentimentScore).reversed());

        int rank = 1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getEntityName().equals(entityName)) {
                rank = i + 1;
                break;
            }
        }
        String leaderName = ranked.get(0).getEntityName();

        return CompetitivePositioning.builder()
                .snapshot(snapshot)
                .totalTracked(snapshot.size())
                .rank(rank)
                .leadsCategory(leaderName.equals(entityName))
                .leaderName(leaderName)
                .build();
    }

    // ------------------------------------------------------------------
    // AuraMath upstream report
    // ------------------------------------------------------------------

    private AuraMathResult fetchAuraMathReport(Long id) {
        String entityId = String.valueOf(id);
        try {
            ResponseEntity<String> upstream = auraMathProxy.forwardEntityReport(
                    WRAPPER_PATH,
                    "/api/marketing/entity-report/" + encodeSegment(entityId),
                    entityId);

            if (upstream.getStatusCode().is2xxSuccessful()) {
                String body = upstream.getBody();
                if (body != null && !body.isBlank()) {
                    return new AuraMathResult(objectMapper.readTree(body), "ok");
                }
            } else {
                log.info("entity-report auramath unavailable id={} status={}",
                        id, upstream.getStatusCode().value());
            }
        } catch (Exception e) {
            log.warn("entity-report auramath fetch failed id={}", id, e);
        }
        return new AuraMathResult(null, "unavailable");
    }

    private record AuraMathResult(JsonNode body, String status) {
    }

    // ------------------------------------------------------------------
    // Deterministic highlights
    // ------------------------------------------------------------------

    private List<String> buildHighlights(EntityDetailResponse entity,
                                         HeadlineMetrics metrics,
                                         CompetitivePositioning positioning,
                                         Map<String, Map<String, Long>> platformReach) {
        List<String> highlights = new ArrayList<>();

        if (metrics.getTotalMentions() > 0) {
            highlights.add(String.format(Locale.US,
                    "%s analysed across %d platform%s of audience conversation",
                    formatCount(metrics.getTotalMentions()),
                    metrics.getPlatformsCovered(),
                    metrics.getPlatformsCovered() == 1 ? "" : "s"));

            highlights.add(String.format(Locale.US,
                    "%.0f%% of all mentions are positive",
                    metrics.getPositivityRatio() * 100));
        }

        if (metrics.getNetSentimentScore() >= 1.0) {
            highlights.add(String.format(Locale.US,
                    "%.1f positive mentions for every negative one",
                    metrics.getNetSentimentScore()));
        }

        if (positioning != null && positioning.getTotalTracked() > 1) {
            if (positioning.isLeadsCategory()) {
                highlights.add(String.format(Locale.US,
                        "Leads its category — #1 of %d tracked titles on net sentiment",
                        positioning.getTotalTracked()));
            } else {
                highlights.add(String.format(Locale.US,
                        "Ranks #%d of %d tracked titles on net sentiment",
                        positioning.getRank(), positioning.getTotalTracked()));
            }
        }

        String topPlatform = topPlatform(platformReach);
        if (topPlatform != null) {
            highlights.add("Strongest reach on " + topPlatform);
        }

        if (entity.getReleaseDate() != null) {
            highlights.add("Tracking sentiment around the " + entity.getReleaseDate() + " release");
        }

        return highlights;
    }

    /** The platform carrying the most total mentions, or {@code null} if none. */
    private String topPlatform(Map<String, Map<String, Long>> platformReach) {
        if (platformReach == null || platformReach.isEmpty()) {
            return null;
        }
        String top = null;
        long best = -1;
        for (Map.Entry<String, Map<String, Long>> e : platformReach.entrySet()) {
            long total = e.getValue() == null ? 0
                    : e.getValue().values().stream().filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
            if (total > best) {
                best = total;
                top = e.getKey();
            }
        }
        return best > 0 ? top : null;
    }

    private static String formatCount(long count) {
        if (count >= 1_000_000) {
            return String.format(Locale.US, "%.1fM mentions", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.US, "%.1fK mentions", count / 1_000.0);
        }
        return count + (count == 1 ? " mention" : " mentions");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Run an optional report section, returning {@code null} (and logging) instead of propagating if it
     * fails — keeps the prospect-facing report resilient to a single flaky source.
     */
    private <T> T optional(String section, Long id, java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("entity-report section '{}' unavailable id={}", section, id, e);
            return null;
        }
    }

    private static String encodeSegment(String segment) {
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
