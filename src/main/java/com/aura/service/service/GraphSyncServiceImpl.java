package com.aura.service.service;

import com.aura.service.entity.GraphEdge;
import com.aura.service.entity.GraphNode;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.enums.GraphRelationType;
import com.aura.service.enums.Platform;
import com.aura.service.repository.GraphEdgeRepository;
import com.aura.service.repository.GraphNodeRepository;
import com.aura.service.repository.MentionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Derivation rules (per product decision, not inferred):
 * <ul>
 *   <li>POSTED_ABOUT — always, for every MOVIE entity a mention is linked to.</li>
 *   <li>RETWEETED — the post content starts with an "RT" marker (e.g. "RT @user: ...").</li>
 *   <li>WATCHED — the post's content is strong evidence the author has seen the movie (references a
 *       specific scene, an actor's performance, etc.), judged by the LLM since this needs semantic
 *       reading of free text, not a keyword match.</li>
 *   <li>LIKED — intentionally not derived yet; no signal for it exists in AuraService's data model.</li>
 *   <li>USER node weight — {@code comments*3 + shares*2 + likes*1}, summed across every movie-linked
 *       mention the author has posted (per product decision: comments outweigh shares 3:2, shares
 *       outweigh likes 2:1). Likes/comments come from the per-platform ingestion tables
 *       (x_posts/youtube_comments/reddit_posts/instagram_posts); none of them track a shares/retweet
 *       count, so "shares" reuses the RETWEETED detection above as its proxy instead of a real count.
 *       Stored in the node's {@code attributes.weight}, recomputed on every sync involving that author.</li>
 * </ul>
 */
@Slf4j
@Service
public class GraphSyncServiceImpl implements GraphSyncService {

    private static final String MOVIE_TYPE = "MOVIE";
    private static final String ATTR_AUTHOR = "author";
    private static final String ATTR_WEIGHT = "weight";
    private static final String MOVIE_NAME_PLACEHOLDER = "[Movie Name]";
    private static final String POST_CONTENT_PLACEHOLDER = "[Post Content]";

    private static final int COMMENT_WEIGHT = 3;
    private static final int SHARE_WEIGHT = 2;
    private static final int LIKE_WEIGHT = 1;

    // "RT" (any case) followed by a separator, at the very start of the post — the standard
    // Twitter-era retweet marker (e.g. "RT @user: ..."). Anchored so words like "Rtx" don't match.
    private static final Pattern RETWEET_PATTERN =
            Pattern.compile("^RT[\\s@:].*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;
    private final MentionRepository mentionRepository;
    private final GraphNodeFactory graphNodeFactory;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    @Value("${llm.prompt.generate.watched.classification}")
    private String watchedClassificationPrompt;

    public GraphSyncServiceImpl(
            GraphNodeRepository graphNodeRepository,
            GraphEdgeRepository graphEdgeRepository,
            MentionRepository mentionRepository,
            GraphNodeFactory graphNodeFactory,
            LLMService llmService,
            ObjectMapper objectMapper) {
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
        this.mentionRepository = mentionRepository;
        this.graphNodeFactory = graphNodeFactory;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void syncMention(Mention mention) {
        String author = mention.getAuthor();
        if (!StringUtils.hasText(author)) {
            return;
        }

        List<ManagedEntity> movies = mention.getManagedEntities().stream()
                .filter(e -> MOVIE_TYPE.equalsIgnoreCase(e.getType()))
                .toList();
        if (movies.isEmpty()) {
            return;
        }

        GraphNode userNode = findOrCreateUserNode(author);
        refreshUserNodeWeight(userNode, author);
        boolean isRetweet = isRetweet(mention.getContent());

        for (ManagedEntity movie : movies) {
            GraphNode movieNode = graphNodeFactory.materializeMovie(movie);

            createEdgeIfAbsent(userNode.getId(), movieNode.getId(),
                    GraphRelationType.POSTED_ABOUT, mention.getPostDate());

            if (isRetweet) {
                createEdgeIfAbsent(userNode.getId(), movieNode.getId(),
                        GraphRelationType.RETWEETED, mention.getPostDate());
            }

            if (classifyWatched(movie.getName(), mention.getContent())) {
                createEdgeIfAbsent(userNode.getId(), movieNode.getId(),
                        GraphRelationType.WATCHED, mention.getPostDate());
            }
        }
    }

    @Override
    public void syncAllMentions() {
        mentionRepository.findAll().forEach(this::syncMention);
    }

    private GraphNode findOrCreateUserNode(String author) {
        return graphNodeRepository.findUserNodeByAuthor(author)
                .orElseGet(() -> {
                    GraphNode node = new GraphNode();
                    node.setType(GraphNodeType.USER);
                    node.setAttributes(Map.of(ATTR_AUTHOR, author));
                    return graphNodeRepository.save(node);
                });
    }

    // Recomputes from scratch (rather than incrementing) so re-syncing is idempotent regardless of
    // processing order, and stays correct if a platform table's counts change after ingestion.
    private void refreshUserNodeWeight(GraphNode userNode, String author) {
        List<Mention> authorMentions = mentionRepository.findMovieLinkedMentionsByAuthor(author);

        Map<Platform, List<String>> postIdsByPlatform = authorMentions.stream()
                .filter(m -> m.getPostId() != null)
                .collect(Collectors.groupingBy(Mention::getPlatform,
                        Collectors.mapping(Mention::getPostId, Collectors.toList())));

        long likes = 0;
        long comments = 0;
        for (Map.Entry<Platform, List<String>> entry : postIdsByPlatform.entrySet()) {
            for (Object[] row : fetchEngagementRows(entry.getKey(), entry.getValue())) {
                likes += toLong(row[1]);
                comments += toLong(row[2]);
            }
        }

        long shares = authorMentions.stream().filter(m -> isRetweet(m.getContent())).count();

        double weight = comments * COMMENT_WEIGHT + shares * SHARE_WEIGHT + likes * LIKE_WEIGHT;

        Map<String, Object> attributes = userNode.getAttributes() == null
                ? new HashMap<>()
                : new HashMap<>(userNode.getAttributes());
        attributes.put(ATTR_WEIGHT, weight);
        userNode.setAttributes(attributes);
        graphNodeRepository.save(userNode);
    }

    private List<Object[]> fetchEngagementRows(Platform platform, List<String> postIds) {
        return switch (platform) {
            case X -> mentionRepository.findXPostEngagement(postIds);
            case YOUTUBE -> mentionRepository.findYoutubeCommentEngagement(postIds);
            case REDDIT -> mentionRepository.findRedditPostEngagement(postIds);
            case INSTAGRAM -> mentionRepository.findInstagramPostEngagement(postIds);
        };
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private void createEdgeIfAbsent(
            Long fromNodeId, Long toNodeId, GraphRelationType relationType, Instant timestamp) {
        if (graphEdgeRepository.existsByFromNodeIdAndToNodeIdAndRelationType(fromNodeId, toNodeId, relationType)) {
            return;
        }
        GraphEdge edge = new GraphEdge();
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setRelationType(relationType);
        edge.setTimestamp(timestamp);
        graphEdgeRepository.save(edge);
    }

    private boolean isRetweet(String content) {
        return content != null && RETWEET_PATTERN.matcher(content.trim()).matches();
    }

    private boolean classifyWatched(String movieName, String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }

        String prompt = watchedClassificationPrompt
                .replace(MOVIE_NAME_PLACEHOLDER, movieName)
                .replace(POST_CONTENT_PLACEHOLDER, content);
        String reply = llmService.generateReply(prompt);

        try {
            JsonNode node = objectMapper.readTree(reply);
            return node.hasNonNull("watched") && node.get("watched").asBoolean(false);
        } catch (Exception e) {
            log.warn("WATCHED classification LLM response could not be parsed as JSON; defaulting to false. Raw: {}",
                    reply, e);
            return false;
        }
    }
}
