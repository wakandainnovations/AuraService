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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link GraphSyncServiceImpl}'s derivation rules: POSTED_ABOUT is unconditional for every linked
 * MOVIE entity, RETWEETED is a pure content-prefix check ("RT" marker), WATCHED is delegated to the LLM
 * since it needs semantic reading of free text, and the USER node's weight is
 * {@code comments*3 + shares*2 + likes*1} summed across the author's movie-linked mentions (shares
 * reusing the RETWEETED "RT" detection, since none of the platform ingestion tables track a real share
 * count). Also covers the find-or-create node dedup and the edge-exists idempotency guard. Collaborators
 * are mocked as interfaces ({@link GraphNodeRepository}, {@link GraphEdgeRepository},
 * {@link MentionRepository}, {@link LLMService}) — never concrete classes.
 */
class GraphSyncServiceImplTest {

    private static final String PROMPT_TEMPLATE = "Movie: [Movie Name] Post: [Post Content]";
    private static final Long USER_NODE_ID = 100L;
    private static final Long MOVIE_NODE_ID = 200L;
    private static final Long MOVIE_ENTITY_ID = 5L;

    private GraphNodeRepository graphNodeRepository;
    private GraphEdgeRepository graphEdgeRepository;
    private MentionRepository mentionRepository;
    private LLMService llmService;
    private GraphSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        graphNodeRepository = mock(GraphNodeRepository.class);
        graphEdgeRepository = mock(GraphEdgeRepository.class);
        mentionRepository = mock(MentionRepository.class);
        llmService = mock(LLMService.class);
        service = new GraphSyncServiceImpl(
                graphNodeRepository, graphEdgeRepository, mentionRepository, llmService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "watchedClassificationPrompt", PROMPT_TEMPLATE);

        when(llmService.generateReply(any())).thenReturn("{\"watched\": false, \"rationale\": \"no signal\"}");
        when(mentionRepository.findMovieLinkedMentionsByAuthor(any())).thenReturn(List.of());
    }

    @Test
    void writesPostedAboutEdgeForEveryLinkedMovie() {
        Mention mention = mentionOf("critic1", "Just saw the trailer.");
        mockNoExistingNodes();

        service.syncMention(mention);

        verify(graphEdgeRepository).save(argThatEdge(GraphRelationType.POSTED_ABOUT));
    }

    @Test
    void retweetPrefixAddsRetweetedEdge() {
        Mention mention = mentionOf("fan1", "RT @studio: unmissable film!");
        mockNoExistingNodes();

        service.syncMention(mention);

        verify(graphEdgeRepository).save(argThatEdge(GraphRelationType.RETWEETED));
    }

    @Test
    void plainPostWithoutRetweetPrefixDoesNotAddRetweetedEdge() {
        Mention mention = mentionOf("fan1", "This movie was great, loved the climax.");
        mockNoExistingNodes();
        when(llmService.generateReply(any())).thenReturn("{\"watched\": true}");

        service.syncMention(mention);

        verify(graphEdgeRepository, never()).save(argThatEdge(GraphRelationType.RETWEETED));
    }

    @Test
    void wordStartingWithRtIsNotMisdetectedAsRetweet() {
        // Regression: "Rtx graphics card" must not match the RT retweet marker.
        Mention mention = mentionOf("fan1", "Rtx graphics made the CGI look amazing.");
        mockNoExistingNodes();

        service.syncMention(mention);

        verify(graphEdgeRepository, never()).save(argThatEdge(GraphRelationType.RETWEETED));
    }

    @Test
    void llmWatchedTrueAddsWatchedEdge() {
        Mention mention = mentionOf("fan1", "The lead actor's performance in that final scene was incredible.");
        mockNoExistingNodes();
        when(llmService.generateReply(any())).thenReturn("{\"watched\": true, \"rationale\": \"references a scene\"}");

        service.syncMention(mention);

        verify(graphEdgeRepository).save(argThatEdge(GraphRelationType.WATCHED));
    }

    @Test
    void llmWatchedFalseDoesNotAddWatchedEdge() {
        Mention mention = mentionOf("fan1", "Can't wait for this movie to release!");
        mockNoExistingNodes();
        when(llmService.generateReply(any())).thenReturn("{\"watched\": false, \"rationale\": \"just hype\"}");

        service.syncMention(mention);

        verify(graphEdgeRepository, never()).save(argThatEdge(GraphRelationType.WATCHED));
    }

    @Test
    void malformedLlmResponseDefaultsWatchedToFalseRatherThanThrowing() {
        Mention mention = mentionOf("fan1", "some post");
        mockNoExistingNodes();
        when(llmService.generateReply(any())).thenReturn("not valid json");

        service.syncMention(mention);

        verify(graphEdgeRepository, never()).save(argThatEdge(GraphRelationType.WATCHED));
    }

    @Test
    void reusesExistingUserAndMovieNodesInsteadOfCreatingDuplicates() {
        // The USER node still gets saved once, to persist its refreshed weight — dedup means no NEW
        // node is created (the movie node, which has no weight, is never re-saved) and the edge is
        // written against the pre-existing ids rather than freshly generated ones.
        Mention mention = mentionOf("fan1", "great movie");
        GraphNode existingUser = nodeWithId(USER_NODE_ID, GraphNodeType.USER);
        GraphNode existingMovie = nodeWithId(MOVIE_NODE_ID, GraphNodeType.MOVIE);
        when(graphNodeRepository.findUserNodeByAuthor("fan1")).thenReturn(Optional.of(existingUser));
        when(graphNodeRepository.findMovieNodeByManagedEntityId(MOVIE_ENTITY_ID)).thenReturn(Optional.of(existingMovie));
        when(graphNodeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.syncMention(mention);

        verify(graphNodeRepository, never()).save(argThat(node -> node.getType() == GraphNodeType.MOVIE));
        verify(graphEdgeRepository).save(argThat(edge ->
                edge.getRelationType() == GraphRelationType.POSTED_ABOUT
                        && edge.getFromNodeId().equals(USER_NODE_ID)
                        && edge.getToNodeId().equals(MOVIE_NODE_ID)));
    }

    @Test
    void reusingExistingUserNodeWithNullAttributesDoesNotThrow() {
        // Regression: nodeWithId (and any pre-existing row with no attributes yet) leaves
        // attributes null; refreshUserNodeWeight must not NPE trying to copy it.
        Mention mention = mentionOf("fan1", "great movie");
        GraphNode existingUser = nodeWithId(USER_NODE_ID, GraphNodeType.USER);
        GraphNode existingMovie = nodeWithId(MOVIE_NODE_ID, GraphNodeType.MOVIE);
        when(graphNodeRepository.findUserNodeByAuthor("fan1")).thenReturn(Optional.of(existingUser));
        when(graphNodeRepository.findMovieNodeByManagedEntityId(MOVIE_ENTITY_ID)).thenReturn(Optional.of(existingMovie));

        service.syncMention(mention);

        assertThat(existingUser.getAttributes()).containsEntry("weight", 0.0);
    }

    @Test
    void existingPostedAboutEdgeIsNotDuplicated() {
        Mention mention = mentionOf("fan1", "great movie");
        mockNoExistingNodes();
        when(graphEdgeRepository.existsByFromNodeIdAndToNodeIdAndRelationType(
                USER_NODE_ID, MOVIE_NODE_ID, GraphRelationType.POSTED_ABOUT)).thenReturn(true);

        service.syncMention(mention);

        verify(graphEdgeRepository, never()).save(argThatEdge(GraphRelationType.POSTED_ABOUT));
    }

    @Test
    void mentionWithNoAuthorIsSkipped() {
        Mention mention = mentionOf(null, "great movie");

        service.syncMention(mention);

        verify(graphNodeRepository, never()).save(any());
        verify(graphEdgeRepository, never()).save(any());
    }

    @Test
    void mentionWithNoLinkedMovieIsSkipped() {
        Mention mention = new Mention();
        mention.setAuthor("fan1");
        mention.setContent("great actor");
        mention.setPostDate(Instant.now());
        ManagedEntity celebrity = new ManagedEntity();
        celebrity.setId(9L);
        celebrity.setType("CELEBRITY");
        mention.addManagedEntity(celebrity);

        service.syncMention(mention);

        verify(graphNodeRepository, never()).save(any());
        verify(graphEdgeRepository, never()).save(any());
    }

    @Test
    void syncAllMentionsSyncsEveryStoredMention() {
        Mention first = mentionOf("fan1", "RT @studio: great film");
        Mention second = mentionOf("fan2", "loved the acting");
        when(mentionRepository.findAll()).thenReturn(List.of(first, second));
        mockNoExistingNodes();

        service.syncAllMentions();

        verify(graphEdgeRepository, times(2)).save(argThatEdge(GraphRelationType.POSTED_ABOUT));
    }

    @Test
    void weightSumsCommentsAndLikesAtDeclaredRatioWithNoShares() {
        Mention mention = mentionOf("fan1", "Loved this movie!");
        mention.setPlatform(Platform.X);
        mention.setPostId("x-1");
        mockNoExistingNodes();
        when(mentionRepository.findMovieLinkedMentionsByAuthor("fan1")).thenReturn(List.of(mention));
        when(mentionRepository.findXPostEngagement(List.of("x-1")))
                .thenReturn(List.<Object[]>of(new Object[]{"x-1", 10, 4}));

        service.syncMention(mention);

        // comments*3 + shares*2 + likes*1 = 4*3 + 0*2 + 10*1 = 22
        assertThat(latestSavedUserNode().getAttributes()).containsEntry("weight", 22.0);
    }

    @Test
    void retweetedMentionCountsAsOneShareInWeight() {
        Mention mention = mentionOf("fan1", "RT @studio: unmissable film!");
        mention.setPlatform(Platform.X);
        mention.setPostId("x-1");
        mockNoExistingNodes();
        when(mentionRepository.findMovieLinkedMentionsByAuthor("fan1")).thenReturn(List.of(mention));
        when(mentionRepository.findXPostEngagement(List.of("x-1")))
                .thenReturn(List.<Object[]>of(new Object[]{"x-1", 5, 1}));

        service.syncMention(mention);

        // comments*3 + shares*2 + likes*1 = 1*3 + 1*2 + 5*1 = 10
        assertThat(latestSavedUserNode().getAttributes()).containsEntry("weight", 10.0);
    }

    @Test
    void weightAggregatesAcrossPlatformsUsingEachTablesOwnColumns() {
        Mention xMention = mentionOf("fan1", "Great film");
        xMention.setPlatform(Platform.X);
        xMention.setPostId("x-1");
        Mention redditMention = mentionOf("fan1", "So good");
        redditMention.setPlatform(Platform.REDDIT);
        redditMention.setPostId("r-1");
        mockNoExistingNodes();
        when(mentionRepository.findMovieLinkedMentionsByAuthor("fan1"))
                .thenReturn(List.of(xMention, redditMention));
        when(mentionRepository.findXPostEngagement(List.of("x-1")))
                .thenReturn(List.<Object[]>of(new Object[]{"x-1", 2, 1}));
        when(mentionRepository.findRedditPostEngagement(List.of("r-1")))
                .thenReturn(List.<Object[]>of(new Object[]{"r-1", 3, 2}));

        service.syncMention(xMention);

        // likes = 2+3 = 5, comments = 1+2 = 3, shares = 0 -> 3*3 + 0*2 + 5*1 = 14
        assertThat(latestSavedUserNode().getAttributes()).containsEntry("weight", 14.0);
    }

    @Test
    void nullEngagementCountsAreTreatedAsZeroRatherThanThrowing() {
        Mention mention = mentionOf("fan1", "Great film");
        mention.setPlatform(Platform.X);
        mention.setPostId("x-1");
        mockNoExistingNodes();
        when(mentionRepository.findMovieLinkedMentionsByAuthor("fan1")).thenReturn(List.of(mention));
        when(mentionRepository.findXPostEngagement(List.of("x-1")))
                .thenReturn(List.<Object[]>of(new Object[]{"x-1", null, null}));

        service.syncMention(mention);

        assertThat(latestSavedUserNode().getAttributes()).containsEntry("weight", 0.0);
    }

    private GraphNode latestSavedUserNode() {
        var captor = forClass(GraphNode.class);
        verify(graphNodeRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
                .filter(node -> node.getType() == GraphNodeType.USER)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private void mockNoExistingNodes() {
        when(graphNodeRepository.findUserNodeByAuthor(any())).thenReturn(Optional.empty());
        when(graphNodeRepository.findMovieNodeByManagedEntityId(any())).thenReturn(Optional.empty());
        when(graphNodeRepository.save(any())).thenAnswer(invocation -> {
            GraphNode node = invocation.getArgument(0);
            node.setId(node.getType() == GraphNodeType.USER ? USER_NODE_ID : MOVIE_NODE_ID);
            return node;
        });
    }

    private static GraphNode nodeWithId(Long id, GraphNodeType type) {
        GraphNode node = new GraphNode();
        node.setId(id);
        node.setType(type);
        return node;
    }

    private static Mention mentionOf(String author, String content) {
        Mention mention = new Mention();
        mention.setAuthor(author);
        mention.setContent(content);
        mention.setPostDate(Instant.now());
        mention.addManagedEntity(movieEntity());
        return mention;
    }

    private static ManagedEntity movieEntity() {
        ManagedEntity movie = new ManagedEntity();
        movie.setId(MOVIE_ENTITY_ID);
        movie.setType("MOVIE");
        movie.setName("Test Movie");
        return movie;
    }

    private static GraphEdge argThatEdge(GraphRelationType relationType) {
        return org.mockito.ArgumentMatchers.argThat(edge -> edge != null && edge.getRelationType() == relationType);
    }
}
