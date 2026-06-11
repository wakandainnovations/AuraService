package com.aura.service.service;

import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImpressionsResolverTest {

    private MentionRepository mentionRepository;
    private ImpressionsResolver resolver;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        resolver = new ImpressionsResolver(mentionRepository);
    }

    private static Mention mention(Long id, Platform platform, String postId) {
        Mention m = new Mention();
        m.setId(id);
        m.setPlatform(platform);
        m.setPostId(postId);
        return m;
    }

    @Test
    void returnsViewsCountForXMentionsAndNaForOtherPlatforms() {
        Mention xMention = mention(1L, Platform.X, "x-1");
        Mention redditMention = mention(2L, Platform.REDDIT, "r-1");
        Mention instagramMention = mention(3L, Platform.INSTAGRAM, "ig-1");
        Mention youtubeMention = mention(4L, Platform.YOUTUBE, "yt-1");

        when(mentionRepository.findXPostViewsCounts(List.of("x-1")))
                .thenReturn(List.<Object[]>of(new Object[]{"x-1", 12345}));

        Map<Long, String> result = resolver.resolveForMentions(
                List.of(xMention, redditMention, instagramMention, youtubeMention));

        assertThat(result).containsEntry(1L, "12345")
                .containsEntry(2L, "NA")
                .containsEntry(3L, "NA")
                .containsEntry(4L, "NA");
    }

    @Test
    void returnsNaWhenXPostRowIsMissingOrViewsCountIsNull() {
        Mention missingRow = mention(1L, Platform.X, "x-missing");
        Mention nullViews = mention(2L, Platform.X, "x-null-views");

        when(mentionRepository.findXPostViewsCounts(List.of("x-missing", "x-null-views")))
                .thenReturn(List.<Object[]>of(new Object[]{"x-null-views", null}));

        Map<Long, String> result = resolver.resolveForMentions(List.of(missingRow, nullViews));

        assertThat(result).containsEntry(1L, "NA").containsEntry(2L, "NA");
    }

    @Test
    void skipsQueryWhenNoXMentionsArePresent() {
        Mention redditMention = mention(1L, Platform.REDDIT, "r-1");

        Map<Long, String> result = resolver.resolveForMentions(List.of(redditMention));

        assertThat(result).containsEntry(1L, "NA");
        verify(mentionRepository, never()).findXPostViewsCounts(anyCollection());
    }

    @Test
    void resolvesSingleMention() {
        Mention xMention = mention(7L, Platform.X, "x-7");
        when(mentionRepository.findXPostViewsCounts(List.of("x-7")))
                .thenReturn(List.<Object[]>of(new Object[]{"x-7", 42}));

        assertThat(resolver.resolveForMention(xMention)).isEqualTo("42");
    }
}
