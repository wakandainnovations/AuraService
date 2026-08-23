package com.aura.service.service;

import com.aura.service.dto.SpreaderPostContent;
import com.aura.service.dto.TopSpreaderContent;
import com.aura.service.dto.TopSpreaderContentResponse;
import com.aura.service.dto.TopSpreaderInsightAction;
import com.aura.service.dto.TopSpreaderInsightsResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.enums.Sentiment;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.proxy.TtlCache;
import com.aura.service.repository.ManagedEntityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends an entity's top-spreader data (see {@link TopSpreaderContentService}) to the LLM to produce
 * collaboration insights for the marketing team: a short summary of which spreaders are delivering the
 * most impact, plus concrete per-spreader collaboration actions. Follows the same "Java computes every
 * number, the LLM only selects and writes prose" split as {@code RecommendedActionsService}: each
 * spreader's {@link RecommendedActionCategory} impact tier is ranked here from real view counts before
 * the LLM ever sees the data, and is merged back onto the LLM's selected actions by {@code spreaderId}
 * rather than ever being LLM-authored. A spreader with no locally-resolved post content is excluded
 * entirely - there's nothing real to ground a collaboration recommendation in.
 *
 * <p>Unlike {@code RecommendedActionsService}, this is a simple per-request call with no persisted
 * cache/refresh cycle - only a short in-memory {@link TtlCache} (same TTL convention as
 * {@link TopSpreaderLookupService}) to avoid re-billing the LLM on every dashboard reload. A call/parse
 * failure is rethrown rather than silently falling back, per this codebase's convention for a
 * single-shot LLM call (see {@code ConflictBalanceServiceImpl}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopSpreaderInsightsService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int SAMPLE_CONTENT_LIMIT = 3;
    private static final int SAMPLE_CONTENT_MAX_CHARS = 240;
    private static final String SPREADER_DATA_PLACEHOLDER = "[Spreader Insights Data]";

    private final TopSpreaderContentService topSpreaderContentService;
    private final ManagedEntityRepository managedEntityRepository;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final TtlCache<TopSpreaderInsightsResponse> cache = new TtlCache<>(1024);

    @Value("${llm.prompt.generate.top.spreader.insights}")
    private String llmPrompt;

    public TopSpreaderInsightsResponse getInsights(
            Long entityId, String language, int spreaderLimit, int postsPerSpreader, boolean refresh) {
        String key = entityId + ":" + (language == null ? "" : language.toLowerCase())
                + ":" + spreaderLimit + ":" + postsPerSpreader;
        if (!refresh) {
            TopSpreaderInsightsResponse cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        ManagedEntity entity = managedEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found with id: " + entityId));
        TopSpreaderContentResponse content = topSpreaderContentService.getTopSpreaderContent(
                entityId, language, spreaderLimit, postsPerSpreader);

        List<SpreaderCandidate> candidates = buildCandidates(content);
        TopSpreaderInsightsResponse response = candidates.isEmpty()
                ? new TopSpreaderInsightsResponse(entityId, language, "", List.of(), clock.instant())
                : generate(entity, entityId, language, candidates);

        cache.put(key, response, CACHE_TTL.toNanos());
        return response;
    }

    /**
     * Ranks spreaders with at least one resolved post by total views (the only real reach proxy
     * AuraMath's top-spreaders endpoint provides - same convention {@code TopSpreaderContentService}
     * and {@code MobilizeAlliesService} already rely on) and buckets them into thirds:
     * {@link RecommendedActionCategory#HIGH_IMPACT} for the top third, {@code MEDIUM_IMPACT} for the
     * next, {@code LOW_IMPACT} for the rest. Package-private (rather than private) so
     * {@code TopSpreaderInsightsServiceTest} can exercise the bucketing directly without mocking the
     * concrete {@link TopSpreaderContentService} - see mockito-no-concrete-class-mocks project note.
     */
    List<SpreaderCandidate> buildCandidates(TopSpreaderContentResponse content) {
        List<TopSpreaderContent> withContent = content.spreaders().stream()
                .filter(s -> !s.topContent().isEmpty())
                .sorted(Comparator.comparingLong(TopSpreaderContent::totalViews).reversed())
                .toList();
        if (withContent.isEmpty()) {
            return List.of();
        }

        int total = withContent.size();
        int highCount = (int) Math.ceil(total / 3.0);
        int mediumCount = (int) Math.ceil((total - highCount) / 2.0);

        List<SpreaderCandidate> candidates = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            RecommendedActionCategory impact = i < highCount
                    ? RecommendedActionCategory.HIGH_IMPACT
                    : i < highCount + mediumCount
                            ? RecommendedActionCategory.MEDIUM_IMPACT
                            : RecommendedActionCategory.LOW_IMPACT;
            candidates.add(toCandidate(withContent.get(i), impact));
        }
        return candidates;
    }

    private SpreaderCandidate toCandidate(TopSpreaderContent spreader, RecommendedActionCategory impact) {
        EnumMap<Sentiment, Long> sentimentCounts = new EnumMap<>(Sentiment.class);
        List<Double> engagementRates = new ArrayList<>();
        List<String> sampleContent = new ArrayList<>();
        for (SpreaderPostContent post : spreader.topContent()) {
            if (post.sentiment() != null && post.sentiment() != Sentiment.TOTAL) {
                sentimentCounts.merge(post.sentiment(), 1L, Long::sum);
            }
            if (post.engagementRate() != null) {
                engagementRates.add(post.engagementRate());
            }
            if (post.content() != null && !post.content().isBlank() && sampleContent.size() < SAMPLE_CONTENT_LIMIT) {
                sampleContent.add(truncate(post.content(), SAMPLE_CONTENT_MAX_CHARS));
            }
        }

        Sentiment dominantSentiment = sentimentCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        Double avgEngagementRate = engagementRates.isEmpty() ? null
                : engagementRates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        return new SpreaderCandidate(
                spreader.globalUserId(), impact, spreader.totalViews(), avgEngagementRate,
                dominantSentiment,
                sentimentCounts.getOrDefault(Sentiment.POSITIVE, 0L),
                sentimentCounts.getOrDefault(Sentiment.NEGATIVE, 0L),
                sentimentCounts.getOrDefault(Sentiment.NEUTRAL, 0L),
                sampleContent);
    }

    private static String truncate(String text, int maxChars) {
        String trimmed = text.strip();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars).strip() + "...";
    }

    /**
     * The one LLM call in this feature: pick which spreaders are worth a collaboration recommendation
     * and write the summary/action prose. Never asks the LLM for (and never accepts back) an impact
     * field - that's merged onto the selection from this candidate's own server-computed tier by
     * {@code spreaderId}.
     */
    private TopSpreaderInsightsResponse generate(
            ManagedEntity entity, Long entityId, String language, List<SpreaderCandidate> candidates) {
        Map<String, SpreaderCandidate> byId = new LinkedHashMap<>();
        for (SpreaderCandidate c : candidates) {
            byId.put(c.spreaderId(), c);
        }

        String reply = llmService.generateReply(buildPrompt(entity, language, candidates));

        JsonNode node;
        try {
            node = objectMapper.readTree(reply);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Top-spreader insights LLM response could not be parsed as JSON for entity " + entityId, e);
        }

        String summary = node.hasNonNull("summary") ? node.get("summary").asText().trim() : "";
        JsonNode actionsNode = node.get("actions");
        if (summary.isEmpty() || actionsNode == null || !actionsNode.isArray()) {
            throw new RuntimeException(
                    "Top-spreader insights LLM response was missing summary/actions for entity " + entityId);
        }

        List<TopSpreaderInsightAction> actions = new ArrayList<>();
        for (JsonNode item : actionsNode) {
            String spreaderId = item.hasNonNull("spreaderId") ? item.get("spreaderId").asText() : null;
            SpreaderCandidate candidate = spreaderId == null ? null : byId.get(spreaderId);
            if (candidate == null) {
                log.warn("Top-spreader insights LLM response referenced unknown spreaderId '{}' for entity {} — dropping",
                        spreaderId, entityId);
                continue;
            }
            String action = item.hasNonNull("action") ? item.get("action").asText().trim() : "";
            if (action.isEmpty()) {
                continue;
            }
            actions.add(new TopSpreaderInsightAction(spreaderId, action, candidate.impact()));
        }

        return new TopSpreaderInsightsResponse(entityId, language, summary, actions, clock.instant());
    }

    private String buildPrompt(ManagedEntity entity, String language, List<SpreaderCandidate> candidates) {
        ObjectNode root = objectMapper.createObjectNode();

        ObjectNode movie = root.putObject("movie");
        movie.put("name", entity.getName());
        putIfPresent(movie, "language", language != null ? language : entity.getLanguage());

        ArrayNode spreadersNode = root.putArray("spreaders");
        for (SpreaderCandidate c : candidates) {
            ObjectNode n = spreadersNode.addObject();
            n.put("spreaderId", c.spreaderId());
            n.put("impact", c.impact().name());
            n.put("totalViews", c.totalViews());
            if (c.avgEngagementRate() != null) {
                n.put("avgEngagementRate", c.avgEngagementRate());
            }
            putIfPresent(n, "dominantSentiment", c.dominantSentiment() == null ? null : c.dominantSentiment().name());
            ObjectNode sentimentCounts = n.putObject("sentimentPostCounts");
            sentimentCounts.put("positive", c.positiveCount());
            sentimentCounts.put("negative", c.negativeCount());
            sentimentCounts.put("neutral", c.neutralCount());
            ArrayNode samples = n.putArray("samplePostContent");
            c.sampleContent().forEach(samples::add);
        }

        return llmPrompt.replace(SPREADER_DATA_PLACEHOLDER, root.toString());
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    /**
     * Phase-1 fully-numeric candidate for one spreader - every field computed from real data, never an
     * LLM guess. {@code impact} is the server-computed reach tier (see {@link #buildCandidates}); the
     * LLM may only select this spreader and write prose about it, never alter or invent this field.
     */
    record SpreaderCandidate(
            String spreaderId,
            RecommendedActionCategory impact,
            long totalViews,
            Double avgEngagementRate,
            Sentiment dominantSentiment,
            long positiveCount,
            long negativeCount,
            long neutralCount,
            List<String> sampleContent) {
    }
}
