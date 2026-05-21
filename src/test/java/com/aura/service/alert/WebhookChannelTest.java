package com.aura.service.alert;

import com.aura.service.entity.SentimentAlert;
import com.aura.service.entity.User;
import com.aura.service.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookChannelTest {

    private MockWebServer server;
    private UserRepository userRepository;
    private WebhookChannel channel;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        userRepository = mock(UserRepository.class);
        channel = new WebhookChannel(userRepository);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private User user(String name, String webhookUrl) {
        User u = new User();
        u.setUsername(name);
        u.setPassword("x");
        u.setRole("USER");
        u.setAlertWebhookUrl(webhookUrl);
        return u;
    }

    private SentimentAlert alert() {
        return SentimentAlert.builder()
                .id(77L)
                .managedEntityId(11L)
                .kind(SentimentAlert.Kind.INFLUENCER_NEGATIVE)
                .status(SentimentAlert.Status.OPEN)
                .triggeredAt(Instant.parse("2026-05-21T12:00:00Z"))
                .matchedAuthor("alice")
                .permalink("https://x.com/alice/1")
                .sourceMentionId(9001L)
                .build();
    }

    @Test
    void postsJsonPayloadToConfiguredUserUrl() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        String url = server.url("/hooks/aura").toString();
        when(userRepository.findAll()).thenReturn(List.of(user("ops", url)));

        channel.send(alert(), "Galaxy Quest");

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/hooks/aura");
        assertThat(req.getHeader("Content-Type")).startsWith("application/json");

        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        assertThat(body.get("id").asLong()).isEqualTo(77L);
        assertThat(body.get("managedEntityId").asLong()).isEqualTo(11L);
        assertThat(body.get("entityName").asText()).isEqualTo("Galaxy Quest");
        assertThat(body.get("kind").asText()).isEqualTo("INFLUENCER_NEGATIVE");
        assertThat(body.get("matchedAuthor").asText()).isEqualTo("alice");
        assertThat(body.get("permalink").asText()).isEqualTo("https://x.com/alice/1");
    }

    @Test
    void skipsUsersWithNullOrBlankWebhook() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        String url = server.url("/hooks/aura").toString();
        when(userRepository.findAll()).thenReturn(List.of(
                user("a", null),
                user("b", "  "),
                user("c", url)
        ));

        channel.send(alert(), "Galaxy Quest");

        assertThat(server.getRequestCount()).isEqualTo(1);
        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/hooks/aura");
    }

    @Test
    void continuesAfterPerUserFailure() throws Exception {
        // First user's webhook returns 500, second user's webhook returns 200.
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(200));
        String first = server.url("/hooks/first").toString();
        String second = server.url("/hooks/second").toString();
        when(userRepository.findAll()).thenReturn(List.of(
                user("a", first),
                user("b", second)
        ));

        channel.send(alert(), "Galaxy Quest");

        assertThat(server.getRequestCount()).isEqualTo(2);
    }
}
