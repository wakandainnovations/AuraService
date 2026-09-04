package com.aura.service.service;

import com.aura.service.dto.ReviewAspectBackfillResponse;
import com.aura.service.dto.ReviewAspectBreakdownResponse;
import com.aura.service.dto.ReviewAspectStat;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.enums.ReviewAspectCategory;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ReviewAspectBreakdownService}'s responsibilities: turning the repository's GROUP BY
 * rows into a ranked/shared response ({@link #buildsRankedBreakdownFromRepositoryRows}); (on
 * {@code refresh=true}) classifying this entity's pending backlog via the LLM before aggregating,
 * defaulting any post the LLM didn't return a usable category for to OTHER
 * ({@link #refreshTrueClassifiesPendingBacklogBeforeAggregating},
 * {@link #malformedLlmReplyDefaultsPendingBatchToOther}); and the unbounded admin backfill draining a
 * whole backlog page by page, including its blank-content termination guarantee
 * ({@link #backfillEntityDrainsBacklogPageByPageUntilExhausted},
 * {@link #backfillEntityDefaultsBlankContentMentionsToOtherWithoutCallingLlm}), and the trigger's
 * dedupe against a repeated call for an entity already backfilling
 * ({@link #triggerBackfillIsANoOpWhenAlreadyInProgress}). {@code self} is wired to the instance itself
 * so {@code @Async}'s self-invocation indirection runs synchronously and deterministically under test —
 * same convention as {@code TopSpreaderInsightsServiceTest}. Collaborators are mocked as interfaces
 * ({@link MentionRepository}, {@link ManagedEntityRepository}, {@link LLMService}) — never concrete
 * classes.
 */
class ReviewAspectBreakdownServiceTest {

    private static final Long ENTITY_ID = 42L;
    private static final String PROMPT_TEMPLATE = "Movie: [Movie Name] Posts: [Posts JSON]";

    private MentionRepository mentionRepository;
    private ManagedEntityRepository managedEntityRepository;
    private LLMService llmService;
    private ReviewAspectBreakdownService service;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        managedEntityRepository = mock(ManagedEntityRepository.class);
        llmService = mock(LLMService.class);
        service = new ReviewAspectBreakdownService(
                mentionRepository, managedEntityRepository, llmService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "classificationPrompt", PROMPT_TEMPLATE);
        ReflectionTestUtils.setField(service, "self", service);

        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        entity.setName("Lord Gaaga");
        when(managedEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
    }

    @Test
    void buildsRankedBreakdownFromRepositoryRows() {
        when(mentionRepository.findReviewAspectBreakdownForEntity(ENTITY_ID)).thenReturn(List.of(
                new Object[]{ReviewAspectCategory.MUSIC_SONGS, 6L, 1.5},
                new Object[]{ReviewAspectCategory.CLIMAX, 3L, -0.5},
                new Object[]{ReviewAspectCategory.OTHER, 1L, 0.0}
        ));

        ReviewAspectBreakdownResponse response = service.getBreakdown(ENTITY_ID, false);

        assertThat(response.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(response.getEntityName()).isEqualTo("Lord Gaaga");
        assertThat(response.getTotalClassifiedPosts()).isEqualTo(10L);

        List<ReviewAspectStat> aspects = response.getAspects();
        assertThat(aspects).hasSize(3);
        assertThat(aspects.get(0).getCategory()).isEqualTo("music_songs");
        assertThat(aspects.get(0).getRank()).isEqualTo(1);
        assertThat(aspects.get(0).getTotalPosts()).isEqualTo(6L);
        assertThat(aspects.get(0).getAverageSentimentScore()).isEqualTo(1.5);
        assertThat(aspects.get(0).getSharePct()).isEqualTo(60.0);

        assertThat(aspects.get(1).getCategory()).isEqualTo("climax");
        assertThat(aspects.get(1).getRank()).isEqualTo(2);

        assertThat(aspects.get(2).getCategory()).isEqualTo("other");
        assertThat(aspects.get(2).getRank()).isEqualTo(3);

        verify(llmService, never()).generateReply(any());
    }

    @Test
    void refreshTrueClassifiesPendingBacklogBeforeAggregating() {
        Mention pending = mentionOf(101L, "The background score in the climax fight was unreal.");
        when(mentionRepository.findUnclassifiedReviewAspectMentions(any(Long.class), any(Pageable.class)))
                .thenReturn(List.of(pending));
        when(llmService.generateReply(any())).thenReturn(
                "{\"classifications\": [{\"id\": 101, \"category\": \"MUSIC_SONGS\"}]}");
        when(mentionRepository.findReviewAspectBreakdownForEntity(ENTITY_ID)).thenReturn(List.of());

        service.getBreakdown(ENTITY_ID, true);

        var captor = forClass(List.class);
        verify(mentionRepository).saveAll(captor.capture());
        List<Mention> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getReviewAspectCategory()).isEqualTo(ReviewAspectCategory.MUSIC_SONGS);
    }

    @Test
    void malformedLlmReplyDefaultsPendingBatchToOther() {
        Mention pending = mentionOf(202L, "some post content");
        when(mentionRepository.findUnclassifiedReviewAspectMentions(any(Long.class), any(Pageable.class)))
                .thenReturn(List.of(pending));
        when(llmService.generateReply(any())).thenReturn("not valid json");
        when(mentionRepository.findReviewAspectBreakdownForEntity(ENTITY_ID)).thenReturn(List.of());

        service.getBreakdown(ENTITY_ID, true);

        var captor = forClass(List.class);
        verify(mentionRepository).saveAll(captor.capture());
        List<Mention> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getReviewAspectCategory()).isEqualTo(ReviewAspectCategory.OTHER);
    }

    @Test
    void refreshFalseDoesNotTouchThePendingBacklog() {
        when(mentionRepository.findReviewAspectBreakdownForEntity(ENTITY_ID)).thenReturn(List.of());

        service.getBreakdown(ENTITY_ID, false);

        verify(mentionRepository, never()).findUnclassifiedReviewAspectMentions(anyLong(), any(Pageable.class));
        verify(llmService, never()).generateReply(any());
    }

    @Test
    void backfillEntityDrainsBacklogPageByPageUntilExhausted() {
        Mention m1 = mentionOf(301L, "The story dragged in the second half.");
        Mention m2 = mentionOf(302L, "VFX in the climax were top notch.");
        when(mentionRepository.findUnclassifiedReviewAspectMentions(eq(ENTITY_ID), any(Pageable.class)))
                .thenReturn(List.of(m1, m2), List.of());
        when(llmService.generateReply(any())).thenReturn(
                "{\"classifications\": [{\"id\": 301, \"category\": \"STORY\"}, {\"id\": 302, \"category\": \"VFX\"}]}");

        ReviewAspectBackfillResponse response = service.triggerBackfill(ENTITY_ID);

        assertThat(response.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(response.getEntityName()).isEqualTo("Lord Gaaga");
        assertThat(response.getStatus()).isEqualTo("started");
        // self is wired to the instance itself in tests, so the @Async call runs synchronously here —
        // by the time triggerBackfill returns, the drain below has already completed.
        assertThat(m1.getReviewAspectCategory()).isEqualTo(ReviewAspectCategory.STORY);
        assertThat(m2.getReviewAspectCategory()).isEqualTo(ReviewAspectCategory.VFX);
        // One fetch that returns work, one that finds the backlog drained — the loop must stop there.
        verify(mentionRepository, times(2))
                .findUnclassifiedReviewAspectMentions(eq(ENTITY_ID), any(Pageable.class));
    }

    @Test
    void backfillEntityDefaultsBlankContentMentionsToOtherWithoutCallingLlm() {
        // A blank-content post is filtered out of every LLM batch (see classifyMentions), so unlike the
        // bounded refresh/sweep paths, an unbounded backfill loop must classify it some other way here
        // or it would keep reappearing in every page and spin forever.
        Mention blank = mentionOf(401L, "   ");
        when(mentionRepository.findUnclassifiedReviewAspectMentions(eq(ENTITY_ID), any(Pageable.class)))
                .thenReturn(List.of(blank), List.of());

        service.triggerBackfill(ENTITY_ID);

        assertThat(blank.getReviewAspectCategory()).isEqualTo(ReviewAspectCategory.OTHER);
        verify(llmService, never()).generateReply(any());
    }

    @Test
    void triggerBackfillIsANoOpWhenAlreadyInProgress() {
        @SuppressWarnings("unchecked")
        Set<Long> inFlight = (Set<Long>) ReflectionTestUtils.getField(service, "inFlightBackfills");
        inFlight.add(ENTITY_ID);

        ReviewAspectBackfillResponse response = service.triggerBackfill(ENTITY_ID);

        assertThat(response.getStatus()).isEqualTo("already_in_progress");
        verify(mentionRepository, never()).findUnclassifiedReviewAspectMentions(anyLong(), any(Pageable.class));
        verify(llmService, never()).generateReply(any());
    }

    private Mention mentionOf(Long id, String content) {
        Mention mention = new Mention();
        mention.setId(id);
        mention.setContent(content);
        return mention;
    }
}
