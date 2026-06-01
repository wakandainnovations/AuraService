package com.aura.service.alert;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aura.service.dto.WhatsChangedResponse;
import com.aura.service.entity.Mention;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.entity.User;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private User testUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("pass");
        user.setRole("ROLE_USER");
        user.setTimezone("UTC");
        return user;
    }

    @Test
    void sendDigestLogsSubjectAndEntityEntries() {
        WhatsChangedResponse delta = new WhatsChangedResponse();
        delta.setSentimentScoreDelta(-0.25);
        delta.setNewMentionsCount(12L);
        delta.setNewNegativeCount(3L);
        delta.setNewSuperSpreaderCount(1L);

        Map<String, WhatsChangedResponse> entries = new LinkedHashMap<>();
        entries.put("Galaxy Quest", delta);

        channel.sendDigest(testUser(), "Your overnight Aura brief: Galaxy Quest", entries, List.of());

        String msg = renderedMessage();
        assertThat(msg).contains("EMAIL DIGEST to=alice");
        assertThat(msg).contains("Your overnight Aura brief: Galaxy Quest");
        assertThat(msg).contains("--- Galaxy Quest ---");
        assertThat(msg).contains("Sentiment delta : -0.25");
        assertThat(msg).contains("New mentions    : 12");
        assertThat(msg).contains("New negatives   : 3");
        assertThat(msg).contains("Super-spreaders : 1");
    }

    @Test
    void sendDigestRendersImpactHighlights() {
        WhatsChangedResponse delta = new WhatsChangedResponse();
        delta.setNewMentionsCount(5L);

        Map<String, WhatsChangedResponse> entries = new LinkedHashMap<>();
        entries.put("Galaxy Quest", delta);

        channel.sendDigest(testUser(), "subject", entries,
                List.of("Your playbook library has handled 12 crises.",
                        "Your templates have saved you 27 drafts."));

        String msg = renderedMessage();
        assertThat(msg).contains("Your impact so far:");
        assertThat(msg).contains("Your playbook library has handled 12 crises.");
        assertThat(msg).contains("Your templates have saved you 27 drafts.");
    }

    @Test
    void sendDigestOmitsImpactBlockWhenNoHighlights() {
        WhatsChangedResponse delta = new WhatsChangedResponse();
        delta.setNewMentionsCount(5L);

        Map<String, WhatsChangedResponse> entries = new LinkedHashMap<>();
        entries.put("Galaxy Quest", delta);

        channel.sendDigest(testUser(), "subject", entries, List.of());

        assertThat(renderedMessage()).doesNotContain("Your impact so far:");
    }

    @Test
    void sendDigestIncludesCompetitorDeltas() {
        WhatsChangedResponse delta = new WhatsChangedResponse();
        delta.setSentimentScoreDelta(0.0);
        delta.setNewMentionsCount(5L);
        delta.setNewNegativeCount(0L);
        delta.setNewSuperSpreaderCount(0L);
        delta.setCompetitorDelta(Map.of("Rival Film", 0.3));

        Map<String, WhatsChangedResponse> entries = new LinkedHashMap<>();
        entries.put("My Film", delta);

        channel.sendDigest(testUser(), "subject", entries, List.of());

        String msg = renderedMessage();
        assertThat(msg).contains("Competitors");
        assertThat(msg).contains("Rival Film");
    }

    @Test
    void sendDigestHandlesNullFieldsGracefully() {
        WhatsChangedResponse delta = new WhatsChangedResponse();

        Map<String, WhatsChangedResponse> entries = new LinkedHashMap<>();
        entries.put("Entity", delta);

        channel.sendDigest(testUser(), "subject", entries, List.of());

        String msg = renderedMessage();
        assertThat(msg).contains("Sentiment delta : 0");
        assertThat(msg).contains("New mentions    : 0");
        assertThat(msg).contains("New negatives   : 0");
        assertThat(msg).contains("Super-spreaders : 0");
        assertThat(msg).doesNotContain("Competitors");
    }

    @Test
    void sendDigestMultipleEntities() {
        WhatsChangedResponse delta1 = new WhatsChangedResponse();
        delta1.setNewMentionsCount(3L);
        WhatsChangedResponse delta2 = new WhatsChangedResponse();
        delta2.setNewMentionsCount(7L);

        Map<String, WhatsChangedResponse> entries = new LinkedHashMap<>();
        entries.put("Entity A", delta1);
        entries.put("Entity B", delta2);

        channel.sendDigest(testUser(), "subject", entries, List.of());

        String msg = renderedMessage();
        assertThat(msg).contains("--- Entity A ---");
        assertThat(msg).contains("--- Entity B ---");
        assertThat(msg).contains("New mentions    : 3");
        assertThat(msg).contains("New mentions    : 7");
    }

    @Test
    void sendDigestFormatsPositiveSentimentDelta() {
        WhatsChangedResponse delta = new WhatsChangedResponse();
        delta.setSentimentScoreDelta(1.5);
        delta.setNewMentionsCount(1L);

        Map<String, WhatsChangedResponse> entries = new LinkedHashMap<>();
        entries.put("Happy Brand", delta);

        channel.sendDigest(testUser(), "subject", entries, List.of());

        String msg = renderedMessage();
        assertThat(msg).contains("Sentiment delta : +1.50");
    }
}
