package com.aura.service.service;

import com.aura.service.dto.AiSummaryResponse;
import com.aura.service.dto.AudiencePulseResponse;
import com.aura.service.dto.AuthorTypeBreakdownResponse;
import com.aura.service.dto.CheckpointImpact;
import com.aura.service.dto.CheckpointImpactResponse;
import com.aura.service.dto.CompetitorSnapshot;
import com.aura.service.dto.ContentIntentBreakdownResponse;
import com.aura.service.dto.EntityStatsResponse;
import com.aura.service.dto.HighlightItem;
import com.aura.service.dto.PromotionalMixResponse;
import com.aura.service.dto.SentimentDeltaResponse;
import com.aura.service.dto.TodaysHighlightsResponse;
import com.aura.service.dto.TopicCategoryBreakdownResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the "AI Summary" and "Today's Highlights" panels shown on the movie Command Center. They are
 * exposed as two separate endpoints ({@link #getAiSummary}/{@link #getTodaysHighlights}) since the UI
 * loads and refreshes them independently, but both read from a single shared generation per entity
 * (one LLM call, one cache entry) so the two panels never tell contradictory stories — whichever
 * endpoint is called first pays the generation cost and the other rides the cache within
 * {@link #CACHE_TTL}.
 *
 * <p>Rather than letting the LLM free-associate, every number it can reference is pre-computed here
 * from real data (the same {@link DashboardService} methods that back their own dedicated endpoints —
 * stats, day-over-day sentiment delta, audience pulse, promotional mix, author-type/content-intent/topic
 * breakdowns, recent checkpoint impact, competitor snapshot) and handed to the model as a JSON "facts"
 * block, with an explicit instruction not to invent anything outside it. See
 * {@link ConflictBalanceServiceImpl} for the same fetch-facts / prompt / parse-JSON shape applied to a
 * narrower (synopsis-only) input.
 */
@Slf4j
@Service
public class CommandCenterSummaryService {

    private static final String ANALYTICS_DATA_PLACEHOLDER = "[Analytics Data]";
    private static final Set<String> VALID_HIGHLIGHT_TYPES = Set.of("POSITIVE", "NEGATIVE", "NEUTRAL");
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final int CHECKPOINT_WINDOW_DAYS = 3;
    private static final int RECENT_CHECKPOINT_LOOKBACK_DAYS = 14;
    private static final int TOP_N = 3;

    private final DashboardService dashboardService;
    private final ManagedEntityRepository managedEntityRepository;
    private final LLMService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<Long, GeneratedContent> cache = new ConcurrentHashMap<>();

    @Value("${llm.prompt.generate.command.center.summary}")
    private String llmPrompt;

    public CommandCenterSummaryService(
            DashboardService dashboardService,
            ManagedEntityRepository managedEntityRepository,
            LLMService llmService) {
        this.dashboardService = dashboardService;
        this.managedEntityRepository = managedEntityRepository;
        this.llmService = llmService;
    }

    public AiSummaryResponse getAiSummary(Long entityId, boolean refresh) {
        GeneratedContent content = getOrGenerate(entityId, refresh);
        return new AiSummaryResponse(entityId, content.entityName(), content.summary(), content.generatedAt());
    }

    public TodaysHighlightsResponse getTodaysHighlights(Long entityId, boolean refresh) {
        GeneratedContent content = getOrGenerate(entityId, refresh);
        return new TodaysHighlightsResponse(entityId, content.entityName(), content.highlights(), content.generatedAt());
    }

    private GeneratedContent getOrGenerate(Long entityId, boolean refresh) {
        GeneratedContent cached = cache.get(entityId);
        if (!refresh && cached != null
                && Duration.between(cached.generatedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached;
        }

        GeneratedContent generated = generate(entityId);
        cache.put(entityId, generated);
        return generated;
    }

    private GeneratedContent generate(Long entityId) {
        ManagedEntity entity = managedEntityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        ObjectNode facts = buildFacts(entityId, entity);

        String prompt = llmPrompt.replace(ANALYTICS_DATA_PLACEHOLDER, facts.toString());
        String reply = llmService.generateReply(prompt);

        JsonNode node;
        try {
            node = objectMapper.readTree(reply);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Command center summary LLM response could not be parsed as JSON for entity " + entityId, e);
        }

        String summary = node.hasNonNull("summary") ? node.get("summary").asText() : "";
        List<HighlightItem> highlights = extractHighlights(node, entityId);

        return new GeneratedContent(entity.getName(), summary, highlights, Instant.now());
    }

    // LLMs are inconsistent about honoring the requested "type" enum (lowercase, missing, or an
    // invented value) — default to NEUTRAL rather than discarding an otherwise-usable highlight.
    // A highlight with no text is dropped entirely rather than rendered blank.
    private List<HighlightItem> extractHighlights(JsonNode node, Long entityId) {
        List<HighlightItem> highlights = new ArrayList<>();
        JsonNode highlightsNode = node.path("highlights");
        if (!highlightsNode.isArray()) {
            return highlights;
        }
        for (JsonNode item : highlightsNode) {
            String text = item.hasNonNull("text") ? item.get("text").asText().trim() : "";
            if (text.isEmpty()) {
                continue;
            }
            String type = item.hasNonNull("type") ? item.get("type").asText().trim().toUpperCase() : "NEUTRAL";
            if (!VALID_HIGHLIGHT_TYPES.contains(type)) {
                log.warn("Command center summary LLM response used unrecognized highlight type '{}' for entity {} — defaulting to NEUTRAL",
                        type, entityId);
                type = "NEUTRAL";
            }
            highlights.add(new HighlightItem(type, text));
        }
        return highlights;
    }

    private ObjectNode buildFacts(Long entityId, ManagedEntity entity) {
        ObjectNode facts = objectMapper.createObjectNode();
        facts.put("movie", entity.getName());

        EntityStatsResponse stats = dashboardService.getEntityStats(entityId);
        ObjectNode totals = facts.putObject("totals");
        totals.put("totalMentions", stats.getTotalMentions());
        totals.put("positiveSentimentPct", pct(stats.getPositiveSentiment()));
        totals.put("negativeSentimentPct", pct(stats.getNegativeSentiment()));
        totals.put("overallSentimentScore", round1(stats.getOverallSentiment()));
        totals.put("netSentimentRatio", round1(stats.getNetSentimentScore()));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        SentimentDeltaResponse delta = dashboardService.getSentimentDelta(entityId, today.minusDays(1), today, 1);
        ObjectNode vsYesterday = facts.putObject("vsYesterday");
        vsYesterday.put("mentionsDelta", delta.getMentionsDelta());
        vsYesterday.put("positiveRatioDeltaPct", pct(delta.getPositiveRatioDelta()));
        vsYesterday.put("netSentimentRatioDelta", round1(delta.getNetSentimentDelta()));

        AudiencePulseResponse pulse = dashboardService.getAudiencePulse(entityId);
        ArrayNode topRegions = facts.putArray("topRegions");
        pulse.getRegions().stream().limit(TOP_N).forEach(r -> {
            ObjectNode n = topRegions.addObject();
            n.put("region", r.getRegion());
            n.put("sharePct", round1(r.getSharePct()));
        });

        PromotionalMixResponse promo = dashboardService.getPromotionalMix(entityId);
        ObjectNode promotionalMix = facts.putObject("promotionalMix");
        promotionalMix.put("totalPosts", promo.getTotalPosts());
        promotionalMix.put("promotionalSharePct", round1(promo.getPromotionalSharePct()));
        promotionalMix.put("organicSharePct", round1(100.0 - promo.getPromotionalSharePct()));

        AuthorTypeBreakdownResponse authorTypes = dashboardService.getAuthorTypeBreakdown(entityId);
        ArrayNode topAuthorTypes = facts.putArray("topAuthorTypes");
        authorTypes.getAuthorTypes().stream().limit(TOP_N).forEach(a -> {
            ObjectNode n = topAuthorTypes.addObject();
            n.put("authorType", a.getAuthorType());
            n.put("sharePct", round1(a.getSharePct()));
        });

        ContentIntentBreakdownResponse intents = dashboardService.getContentIntentBreakdown(entityId);
        ArrayNode topContentIntents = facts.putArray("topContentIntents");
        intents.getIntents().stream().limit(TOP_N).forEach(i -> {
            ObjectNode n = topContentIntents.addObject();
            n.put("contentIntent", i.getContentIntent());
            n.put("sharePct", round1(i.getSharePct()));
        });

        TopicCategoryBreakdownResponse topics = dashboardService.getTopicCategoryBreakdown(entityId);
        ArrayNode topTopics = facts.putArray("topTopics");
        topics.getTopics().stream().limit(TOP_N).forEach(t -> {
            ObjectNode n = topTopics.addObject();
            n.put("topicCategory", t.getTopicCategory());
            n.put("sharePct", round1(t.getSharePct()));
        });

        CheckpointImpactResponse checkpointImpact = dashboardService.getCheckpointImpact(entityId, CHECKPOINT_WINDOW_DAYS);
        ArrayNode recentCheckpoints = facts.putArray("recentCheckpoints");
        LocalDate recentCutoff = today.minusDays(RECENT_CHECKPOINT_LOOKBACK_DAYS);
        checkpointImpact.getImpacts().stream()
                .filter(cp -> !cp.getCheckpointDate().isBefore(recentCutoff) && !cp.getCheckpointDate().isAfter(today))
                .sorted(Comparator.comparing(CheckpointImpact::getCheckpointDate).reversed())
                .limit(TOP_N)
                .forEach(cp -> {
                    ObjectNode n = recentCheckpoints.addObject();
                    n.put("description", cp.getDescription());
                    n.put("date", cp.getCheckpointDate().toString());
                    n.put("impactDirection", cp.getImpactDirection());
                    n.put("positiveRatioChangePct", pct(cp.getPositiveRatioChange()));
                    n.put("netSentimentRatioChange", round1(cp.getNetSentimentChange()));
                });

        List<CompetitorSnapshot> competitorSnapshots = dashboardService.getCompetitorSnapshot(entityId);
        ArrayNode competitors = facts.putArray("competitors");
        competitorSnapshots.stream()
                .filter(c -> !Objects.equals(c.getEntityName(), entity.getName()))
                .forEach(c -> {
                    ObjectNode n = competitors.addObject();
                    n.put("name", c.getEntityName());
                    n.put("totalMentions", c.getTotalMentions());
                    n.put("positiveRatioPct", pct(c.getPositiveRatio()));
                    n.put("netSentimentRatio", round1(c.getNetSentimentScore()));
                });

        return facts;
    }

    private static double pct(double fraction) {
        return round1(fraction * 100.0);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record GeneratedContent(String entityName, String summary, List<HighlightItem> highlights, Instant generatedAt) {
    }
}
