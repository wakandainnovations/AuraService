package com.aura.service.alert;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aura.service.entity.Mention;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoopEmailChannelTest {

    private static final Long ENTITY_ID = 11L;

    private MentionRepository mentionRepository;
    private NoopEmailChannel channel;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        channel = new NoopEmailChannel(mentionRepository);

        logger = (Logger) LoggerFactory.getLogger(NoopEmailChannel.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private Mention mention(String author, String permalink) {
        Mention m = new Mention();
        m.setAuthor(author);
        m.setPermalink(permalink);
        m.setSentiment(Sentiment.NEGATIVE);
        m.setPostDate(Instant.parse("2026-05-21T11:00:00Z"));
        return m;
    }

    private SentimentAlert alert() {
        return SentimentAlert.builder()
                .id(77L)
                .managedEntityId(ENTITY_ID)
                .kind(SentimentAlert.Kind.SPIKE)
                .status(SentimentAlert.Status.OPEN)
                .triggeredAt(Instant.parse("2026-05-21T12:00:00Z"))
                .build();
    }

    private String renderedMessage() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + b);
    }

    @Test
    void logsSubjectWithEntityNameAndTopThreeMentions() {
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentOrderByPostDateDesc(
                ENTITY_ID, Sentiment.NEGATIVE)).thenReturn(List.of(
                        mention("alice", "https://x.com/alice/1"),
                        mention("bob", "https://x.com/bob/2"),
                        mention("carol", "https://x.com/carol/3")
                ));

        channel.send(alert(), "Galaxy Quest");

        verify(mentionRepository).findTop3ByManagedEntityIdAndSentimentOrderByPostDateDesc(
                ENTITY_ID, Sentiment.NEGATIVE);
        String msg = renderedMessage();
        assertThat(msg).contains("[Aura] Galaxy Quest negative spike");
        assertThat(msg).contains("https://x.com/alice/1");
        assertThat(msg).contains("https://x.com/bob/2");
        assertThat(msg).contains("https://x.com/carol/3");
        assertThat(msg).contains("@alice");
    }

    @Test
    void fallsBackToEntityIdWhenEntityNameMissing() {
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentOrderByPostDateDesc(
                ENTITY_ID, Sentiment.NEGATIVE)).thenReturn(List.of());

        channel.send(alert(), null);

        assertThat(renderedMessage()).contains("[Aura] entity #" + ENTITY_ID + " negative spike");
    }

    @Test
    void handlesEmptyTopMentionsGracefully() {
        when(mentionRepository.findTop3ByManagedEntityIdAndSentimentOrderByPostDateDesc(
                ENTITY_ID, Sentiment.NEGATIVE)).thenReturn(List.of());

        channel.send(alert(), "Galaxy Quest");

        assertThat(renderedMessage()).contains("(none found)");
    }
}
