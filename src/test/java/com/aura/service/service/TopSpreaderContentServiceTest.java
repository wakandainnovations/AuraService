package com.aura.service.service;

import com.aura.service.dto.TopSpreaderContent;
import com.aura.service.dto.TopSpreaderContentResponse;
import com.aura.service.entity.EntityLanguageSpreaderSnapshot;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.EntityLanguageSpreaderSnapshotRepository;
import com.aura.service.repository.MentionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopSpreaderContentServiceTest {

    private EntityLanguageSpreaderSnapshotRepository snapshotRepository;
    private MentionRepository mentionRepository;
    private TopSpreaderContentService service;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(EntityLanguageSpreaderSnapshotRepository.class);
        mentionRepository = mock(MentionRepository.class);
        service = new TopSpreaderContentService(snapshotRepository, mentionRepository, new ObjectMapper());
    }

    private static EntityLanguageSpreaderSnapshot snapshot(Long entityId, String language, String spreadersJson) {
        EntityLanguageSpreaderSnapshot s = new EntityLanguageSpreaderSnapshot();
        s.setId(1L);
        s.setEntityId(entityId);
        s.setLanguage(language);
        s.setSpreadersJson(spreadersJson);
        s.setGeneratedAt(Instant.now());
        return s;
    }

    private static Mention mention(Long id, Platform platform, String postId, String author,
                                    Sentiment sentiment, short score) {
        Mention m = new Mention();
        m.setId(id);
        m.setPlatform(platform);
        m.setPostId(postId);
        m.setAuthor(author);
        m.setContent("some post content");
        m.setPostDate(Instant.now());
        m.setSentiment(sentiment);
        m.setSentimentScore(score);
        m.setPermalink("https://example.com/" + postId);
        return m;
    }

    @Test
    void ranksSpreadersByTotalViewsAndAttachesTopContentSortedByViews() {
        String json = "[" +
                "{\"globalUserId\":\"alice\",\"primaryPlatform\":null,\"influenceTier\":null,\"totalViews\":500,\"profileUrl\":\"u/alice\"}," +
                "{\"globalUserId\":\"bob\",\"primaryPlatform\":null,\"influenceTier\":null,\"totalViews\":9000,\"profileUrl\":\"u/bob\"}" +
                "]";
        when(snapshotRepository.findByEntityIdAndLanguageIgnoreCase(10L, "Hindi"))
                .thenReturn(Optional.of(snapshot(10L, "Hindi", json)));

        Mention bobLowView = mention(1L, Platform.X, "x-1", "bob", Sentiment.POSITIVE, (short) 80);
        Mention bobHighView = mention(2L, Platform.X, "x-2", "bob", Sentiment.NEGATIVE, (short) -40);
        when(mentionRepository.findByManagedEntityIdAndAuthorIn(eq(10L), anyCollection()))
                .thenReturn(List.of(bobLowView, bobHighView));
        when(mentionRepository.findXPostViewsCounts(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{"x-1", 100}, new Object[]{"x-2", 900}));
        when(mentionRepository.findXPostEngagement(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{"x-1", 10, 5}, new Object[]{"x-2", 90, 10}));

        TopSpreaderContentResponse response = service.getTopSpreaderContent(10L, "Hindi", 10, 5);

        assertThat(response.spreaders()).hasSize(2);
        // bob (9000 totalViews) ranked above alice (500 totalViews)
        TopSpreaderContent bob = response.spreaders().get(0);
        assertThat(bob.globalUserId()).isEqualTo("bob");
        assertThat(bob.topContent()).hasSize(2);
        // higher-view post (x-2, 900 views) ranked first within bob's content
        assertThat(bob.topContent().get(0).mentionId()).isEqualTo(2L);
        assertThat(bob.topContent().get(0).views()).isEqualTo(900L);
        assertThat(bob.topContent().get(0).engagementRate()).isEqualTo((90 + 10) / 900.0);
        assertThat(bob.topContent().get(1).mentionId()).isEqualTo(1L);

        TopSpreaderContent alice = response.spreaders().get(1);
        assertThat(alice.globalUserId()).isEqualTo("alice");
        assertThat(alice.topContent()).isEmpty();
    }

    @Test
    void engagementRateIsNullWhenViewsUnavailable() {
        String json = "[{\"globalUserId\":\"carol\",\"primaryPlatform\":null,\"influenceTier\":null,\"totalViews\":50,\"profileUrl\":null}]";
        when(snapshotRepository.findByEntityIdAndLanguageIgnoreCase(20L, "Telugu"))
                .thenReturn(Optional.of(snapshot(20L, "Telugu", json)));

        Mention m = mention(3L, Platform.REDDIT, "r-1", "carol", Sentiment.NEUTRAL, (short) 0);
        when(mentionRepository.findByManagedEntityIdAndAuthorIn(eq(20L), anyCollection()))
                .thenReturn(List.of(m));
        when(mentionRepository.findRedditPostViews(anyCollection())).thenReturn(List.<Object[]>of());
        when(mentionRepository.findRedditPostEngagement(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{"r-1", 5, 2}));

        TopSpreaderContentResponse response = service.getTopSpreaderContent(20L, "Telugu", 10, 5);

        assertThat(response.spreaders()).hasSize(1);
        assertThat(response.spreaders().get(0).topContent().get(0).views()).isNull();
        assertThat(response.spreaders().get(0).topContent().get(0).engagementRate()).isNull();
        assertThat(response.spreaders().get(0).topContent().get(0).likes()).isEqualTo(5L);
        assertThat(response.spreaders().get(0).topContent().get(0).comments()).isEqualTo(2L);
    }

    @Test
    void dedupesSpreadersAcrossLanguagesWhenNoLanguageFilterGiven() {
        String jsonEn = "[{\"globalUserId\":\"dave\",\"primaryPlatform\":null,\"influenceTier\":null,\"totalViews\":100,\"profileUrl\":null}]";
        String jsonHi = "[{\"globalUserId\":\"dave\",\"primaryPlatform\":null,\"influenceTier\":null,\"totalViews\":100,\"profileUrl\":null}]";
        when(snapshotRepository.findByEntityId(30L)).thenReturn(List.of(
                snapshot(30L, "English", jsonEn), snapshot(30L, "Hindi", jsonHi)));
        when(mentionRepository.findByManagedEntityIdAndAuthorIn(eq(30L), anyCollection()))
                .thenReturn(List.of());

        TopSpreaderContentResponse response = service.getTopSpreaderContent(30L, null, 10, 5);

        assertThat(response.spreaders()).hasSize(1);
        assertThat(response.language()).isNull();
    }

    @Test
    void returnsEmptySpreadersWhenNoSnapshotExists() {
        when(snapshotRepository.findByEntityIdAndLanguageIgnoreCase(40L, "Tamil")).thenReturn(Optional.empty());

        TopSpreaderContentResponse response = service.getTopSpreaderContent(40L, "Tamil", 10, 5);

        assertThat(response.spreaders()).isEmpty();
        assertThat(response.entityId()).isEqualTo(40L);
    }
}
