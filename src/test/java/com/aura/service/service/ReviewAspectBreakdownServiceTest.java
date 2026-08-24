package com.aura.service.service;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ReviewAspectBreakdownService}'s two responsibilities: turning the repository's
 * GROUP BY rows into a ranked/shared response ({@link #buildsRankedBreakdownFromRepositoryRows}), and
 * (on {@code refresh=true}) classifying this entity's pending backlog via the LLM before aggregating,
 * defaulting any post the LLM didn't return a usable category for to OTHER
 * ({@link #refreshTrueClassifiesPendingBacklogBeforeAggregating},
 * {@link #malformedLlmReplyDefaultsPendingBatchToOther}). Collaborators are mocked as interfaces
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

    private Mention mentionOf(Long id, String content) {
        Mention mention = new Mention();
        mention.setId(id);
        mention.setContent(content);
        return mention;
    }
}
