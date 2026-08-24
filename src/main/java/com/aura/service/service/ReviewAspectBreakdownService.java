package com.aura.service.service;

import com.aura.service.dto.ReviewAspectBreakdownResponse;
import com.aura.service.dto.ReviewAspectStat;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.enums.ReviewAspectCategory;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the "Aspect Sentiment" breakdown panel: for each fixed review aspect (Music/Songs, Direction,
 * Acting/Cast Performance, Story, Screenplay, Lead Pair, Runtime, First Half, Second Half, Climax,
 * VFX, Other — see {@link ReviewAspectCategory}), how many posts talk about it and what their average
 * sentiment score is. Modeled on {@link com.aura.service.repository.MentionRepository#findTopicCategoryBreakdownForEntity},
 * but unlike {@code topic_category} (populated upstream of this service, outside AuraService) this
 * taxonomy has no existing data source: classification is done here, one field per post
 * ({@link Mention#getReviewAspectCategory()}), via a batched LLM call — see {@link #classifyBatch}.
 * Once classified, a post is never re-classified. The breakdown itself is then a plain SQL GROUP BY
 * (see {@code findReviewAspectBreakdownForEntity}) — real per-post {@code sentiment_score} values
 * averaged in the database, never asked of the LLM (see the "LLM never emits numbers" convention: the
 * LLM only ever returns a category label per post id, nothing numeric).
 *
 * <p>A background sweep ({@link #classifyPendingMentions}) works through the global backlog of
 * not-yet-classified posts every 2 hours — global, not per-entity, since classification is a property
 * of a post's own content, independent of which entity(ies) it's linked to, so a post shared by several
 * entities' keywords is never classified twice. This keeps the default ({@code refresh=false}) read
 * path a fast GROUP BY over already-classified data; {@code refresh=true} additionally classifies this
 * entity's own pending backlog synchronously before returning, mirroring
 * {@link AudiencePulseAspectsService}/{@link CommandCenterSummaryService}'s refresh convention.
 */
@Slf4j
@Service
public class ReviewAspectBreakdownService {

    private static final int MAX_MENTIONS_PER_LLM_CALL = 40;
    private static final int MAX_PENDING_PER_ENTITY_REFRESH = 200;
    private static final int MAX_PENDING_PER_SCHEDULED_SWEEP = 500;
    private static final String MOVIE_NAME_PLACEHOLDER = "[Movie Name]";
    private static final String POSTS_JSON_PLACEHOLDER = "[Posts JSON]";
    private static final String FALLBACK_MOVIE_NAME = "the movie";

    private final MentionRepository mentionRepository;
    private final ManagedEntityRepository managedEntityRepository;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    @Value("${llm.prompt.generate.review.aspect.classification}")
    private String classificationPrompt;

    public ReviewAspectBreakdownService(
            MentionRepository mentionRepository,
            ManagedEntityRepository managedEntityRepository,
            LLMService llmService,
            ObjectMapper objectMapper) {
        this.mentionRepository = mentionRepository;
        this.managedEntityRepository = managedEntityRepository;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReviewAspectBreakdownResponse getBreakdown(Long entityId, boolean refresh) {
        ManagedEntity entity = managedEntityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        if (refresh) {
            Pageable page = PageRequest.of(0, MAX_PENDING_PER_ENTITY_REFRESH);
            List<Mention> pending = mentionRepository.findUnclassifiedReviewAspectMentions(entityId, page);
            classifyMentions(entity.getName(), pending);
        }

        return buildBreakdown(entity);
    }

    /**
     * Classifies a bounded, oldest-first slice of the global not-yet-classified backlog every 2 hours,
     * so the default (non-refresh) read path normally has fresh-enough data without paying for an LLM
     * call on request. Each mention uses its own {@link Mention#getPrimaryManagedEntity()} for the
     * movie-name context (falling back to a generic name if a mention is somehow unlinked), and mentions
     * are classified individually per-entity name in one pass rather than looped per entity, so a post
     * shared across several entities' keywords is still classified exactly once.
     */
    @Scheduled(fixedDelayString = "PT2H")
    @Transactional
    public void classifyPendingMentions() {
        Pageable page = PageRequest.of(0, MAX_PENDING_PER_SCHEDULED_SWEEP);
        List<Mention> pending = mentionRepository.findUnclassifiedReviewAspectMentions(page);
        log.info("Classifying {} pending review-aspect mentions", pending.size());

        Map<String, List<Mention>> byMovieName = new HashMap<>();
        for (Mention mention : pending) {
            ManagedEntity primary = mention.getPrimaryManagedEntity();
            String movieName = primary != null ? primary.getName() : FALLBACK_MOVIE_NAME;
            byMovieName.computeIfAbsent(movieName, k -> new ArrayList<>()).add(mention);
        }

        for (Map.Entry<String, List<Mention>> entry : byMovieName.entrySet()) {
            try {
                classifyMentions(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.error("Failed to classify pending review-aspect mentions for movie {}", entry.getKey(), e);
            }
        }
    }

    private void classifyMentions(String movieName, List<Mention> mentions) {
        List<Mention> withContent = mentions.stream()
                .filter(m -> StringUtils.hasText(m.getContent()))
                .toList();
        for (List<Mention> batch : partition(withContent, MAX_MENTIONS_PER_LLM_CALL)) {
            classifyBatch(movieName, batch);
        }
    }

    private void classifyBatch(String movieName, List<Mention> batch) {
        ArrayNode postsJson = objectMapper.createArrayNode();
        for (Mention mention : batch) {
            ObjectNode postNode = postsJson.addObject();
            postNode.put("id", mention.getId());
            postNode.put("content", mention.getContent());
        }

        String prompt = classificationPrompt
                .replace(MOVIE_NAME_PLACEHOLDER, movieName)
                .replace(POSTS_JSON_PLACEHOLDER, postsJson.toString());
        String reply = llmService.generateReply(prompt);

        Map<Long, ReviewAspectCategory> categoriesById = parseClassifications(reply);

        for (Mention mention : batch) {
            mention.setReviewAspectCategory(categoriesById.getOrDefault(mention.getId(), ReviewAspectCategory.OTHER));
        }
        mentionRepository.saveAll(batch);
    }

    // Any post the LLM didn't return a usable classification for (parse failure, missing id, or an
    // invented category value) defaults to OTHER rather than staying unclassified forever and being
    // resent to the LLM on every future sweep.
    private Map<Long, ReviewAspectCategory> parseClassifications(String reply) {
        Map<Long, ReviewAspectCategory> result = new HashMap<>();
        try {
            JsonNode node = objectMapper.readTree(reply);
            JsonNode items = node.path("classifications");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (!item.hasNonNull("id") || !item.hasNonNull("category")) {
                        continue;
                    }
                    result.put(item.get("id").asLong(), parseCategory(item.get("category").asText()));
                }
            }
        } catch (Exception e) {
            log.warn("Review-aspect classification LLM response could not be parsed as JSON; " +
                    "affected posts default to OTHER. Raw: {}", reply, e);
        }
        return result;
    }

    private ReviewAspectCategory parseCategory(String raw) {
        try {
            return ReviewAspectCategory.valueOf(raw.trim().toUpperCase().replace(' ', '_').replace('/', '_'));
        } catch (Exception e) {
            return ReviewAspectCategory.OTHER;
        }
    }

    private ReviewAspectBreakdownResponse buildBreakdown(ManagedEntity entity) {
        List<Object[]> rows = mentionRepository.findReviewAspectBreakdownForEntity(entity.getId());

        long totalClassifiedPosts = 0;
        for (Object[] row : rows) {
            totalClassifiedPosts += ((Number) row[1]).longValue();
        }

        List<ReviewAspectStat> aspects = new ArrayList<>();
        for (Object[] row : rows) {
            ReviewAspectCategory category = (ReviewAspectCategory) row[0];
            long count = ((Number) row[1]).longValue();
            Double averageSentimentScore = row[2] != null ? ((Number) row[2]).doubleValue() : null;
            double sharePct = totalClassifiedPosts > 0 ? (double) count / totalClassifiedPosts * 100.0 : 0.0;
            aspects.add(new ReviewAspectStat(0, category.name().toLowerCase(), count, averageSentimentScore, sharePct));
        }
        aspects.sort(Comparator.comparingLong(ReviewAspectStat::getTotalPosts).reversed());
        for (int i = 0; i < aspects.size(); i++) {
            aspects.get(i).setRank(i + 1);
        }

        return new ReviewAspectBreakdownResponse(entity.getId(), entity.getName(), totalClassifiedPosts, aspects);
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
