package com.aura.service.service;

import com.aura.service.alert.EmailChannel;
import com.aura.service.dto.WhatsChangedResponse;
import com.aura.service.dto.WorkspaceImpactResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.entity.User;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.UserEntityViewRepository;
import com.aura.service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MorningDigestServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ENTITY_ID = 42L;

    private UserRepository userRepository;
    private UserEntityViewRepository viewRepository;
    private ManagedEntityRepository entityRepository;
    private StubWhatsChangedService whatsChangedService;
    private StubWorkspaceImpactService workspaceImpactService;
    private RecordingEmailChannel emailChannel;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        viewRepository = mock(UserEntityViewRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);
        whatsChangedService = new StubWhatsChangedService();
        workspaceImpactService = new StubWorkspaceImpactService();
        emailChannel = new RecordingEmailChannel();
    }

    private MorningDigestService serviceAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new MorningDigestService(
                userRepository, viewRepository, entityRepository,
                whatsChangedService, workspaceImpactService, emailChannel, clock);
    }

    @Test
    void digestSentAt8amUserTimezone() {
        MorningDigestService service = serviceAt("2026-05-23T12:00:00Z");
        User user = userWithTimezone("America/New_York");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(5L, 2L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Test Movie")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).subject).startsWith("Your overnight Aura brief:");
        assertThat(emailChannel.calls.get(0).entries).containsKey("Test Movie");
    }

    @Test
    void noDigestWhenNotEightAm() {
        MorningDigestService service = serviceAt("2026-05-23T15:00:00Z");
        User user = userWithTimezone("America/New_York");
        when(userRepository.findAll()).thenReturn(List.of(user));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).isEmpty();
    }

    @Test
    void utcUserGetsDigestAt8utc() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(3L, 0L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Brand")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
    }

    @Test
    void noDigestWhenUserHasNoEntities() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of());

        service.sendMorningDigests();

        assertThat(emailChannel.calls).isEmpty();
    }

    @Test
    void noDigestWhenAllDeltasEmpty() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, new WhatsChangedResponse());

        service.sendMorningDigests();

        assertThat(emailChannel.calls).isEmpty();
    }

    @Test
    void headlinePrioritizesSuperSpreaders() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(1L, 2L));

        whatsChangedService.put(USER_ID, 1L, deltaWith(100L, 50L));

        WhatsChangedResponse spreaderDelta = new WhatsChangedResponse();
        spreaderDelta.setNewMentionsCount(2L);
        spreaderDelta.setNewNegativeCount(0L);
        spreaderDelta.setNewSuperSpreaderCount(1L);
        whatsChangedService.put(USER_ID, 2L, spreaderDelta);

        when(entityRepository.findById(1L)).thenReturn(Optional.of(entityNamed("Movie A")));
        when(entityRepository.findById(2L)).thenReturn(Optional.of(entityNamed("Movie B")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).subject).contains("Movie B").contains("super-spreader");
    }

    @Test
    void invalidTimezoneFallsBackToUtc() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("Not/A/Zone");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(3L, 1L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Brand")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
    }

    @Test
    void multipleEntitiesIncludedInDigest() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(1L, 2L));
        whatsChangedService.put(USER_ID, 1L, deltaWith(5L, 1L));
        whatsChangedService.put(USER_ID, 2L, deltaWith(10L, 3L));
        when(entityRepository.findById(1L)).thenReturn(Optional.of(entityNamed("Entity A")));
        when(entityRepository.findById(2L)).thenReturn(Optional.of(entityNamed("Entity B")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).entries).hasSize(2);
        assertThat(emailChannel.calls.get(0).entries).containsKeys("Entity A", "Entity B");
    }

    @Test
    void multipleUsersOnlyEligibleOneGetsDigest() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User utcUser = userWithTimezone(1L, "testuser1", "UTC");
        User tokyoUser = userWithTimezone(2L, "testuser2", "Asia/Tokyo");
        when(userRepository.findAll()).thenReturn(List.of(utcUser, tokyoUser));
        when(viewRepository.findEntityIdsByUserId(1L)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(1L, ENTITY_ID, deltaWith(5L, 1L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Brand")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).user.getUsername()).isEqualTo("testuser1");
    }

    @Test
    void headlineShowsNegativeMentionsWhenNoSuperSpreaders() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(10L, 4L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Acme")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls.get(0).subject)
                .isEqualTo("Your overnight Aura brief: Acme picked up 4 negative mentions");
    }

    @Test
    void headlineShowsMentionCountWhenNoNegatives() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(7L, 0L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Acme")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls.get(0).subject)
                .isEqualTo("Your overnight Aura brief: Acme has 7 new mentions");
    }

    @Test
    void headlineSingularMention() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(1L, 0L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Acme")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls.get(0).subject)
                .isEqualTo("Your overnight Aura brief: Acme has 1 new mention");
    }

    @Test
    void headlineSingularNegativeMention() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(3L, 1L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Acme")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls.get(0).subject)
                .isEqualTo("Your overnight Aura brief: Acme picked up 1 negative mention");
    }

    @Test
    void headlineSingularSuperSpreader() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        WhatsChangedResponse delta = new WhatsChangedResponse();
        delta.setNewMentionsCount(1L);
        delta.setNewSuperSpreaderCount(1L);
        whatsChangedService.put(USER_ID, ENTITY_ID, delta);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Acme")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls.get(0).subject)
                .isEqualTo("Your overnight Aura brief: Acme has 1 new super-spreader mention");
    }

    @Test
    void entityNotFoundFallsBackToIdLabel() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(99L));
        whatsChangedService.put(USER_ID, 99L, deltaWith(2L, 0L));
        when(entityRepository.findById(99L)).thenReturn(Optional.empty());

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).entries).containsKey("Entity #99");
    }

    @Test
    void mixedEmptyAndNonEmptyDeltasOnlyIncludesNonEmpty() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(1L, 2L, 3L));
        whatsChangedService.put(USER_ID, 1L, new WhatsChangedResponse());
        whatsChangedService.put(USER_ID, 2L, deltaWith(5L, 1L));
        whatsChangedService.put(USER_ID, 3L, new WhatsChangedResponse());
        when(entityRepository.findById(2L)).thenReturn(Optional.of(entityNamed("Active Entity")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).entries).hasSize(1);
        assertThat(emailChannel.calls.get(0).entries).containsKey("Active Entity");
    }

    @Test
    void errorInOneUserDoesNotPreventOthers() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User badUser = userWithTimezone(1L, "baduser", "UTC");
        User goodUser = userWithTimezone(2L, "gooduser", "UTC");
        when(userRepository.findAll()).thenReturn(List.of(badUser, goodUser));
        when(viewRepository.findEntityIdsByUserId(1L)).thenThrow(new RuntimeException("db error"));
        when(viewRepository.findEntityIdsByUserId(2L)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(2L, ENTITY_ID, deltaWith(3L, 0L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Brand")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).user.getUsername()).isEqualTo("gooduser");
    }

    @Test
    void noDigestAtEightAmNonZeroMinute() {
        MorningDigestService service = serviceAt("2026-05-23T08:30:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).isEmpty();
    }

    @Test
    void positiveOffsetTimezoneEligibleAt8am() {
        // 02:30 UTC = 08:00 IST (UTC+5:30)
        MorningDigestService service = serviceAt("2026-05-23T02:30:00Z");
        User user = userWithTimezone("Asia/Kolkata");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(4L, 0L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Bollywood Film")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
    }

    @Test
    void sentimentDeltaOnlyCountsAsNonEmpty() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        WhatsChangedResponse delta = new WhatsChangedResponse();
        delta.setSentimentScoreDelta(1.5);
        delta.setNewMentionsCount(0L);
        whatsChangedService.put(USER_ID, ENTITY_ID, delta);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Brand")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).entries).containsKey("Brand");
    }

    @Test
    void nullTimezoneFieldFallsBackToUtcAt8am() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("nulltz");
        user.setPassword("pass");
        user.setRole("ROLE_USER");
        user.setTimezone(null);
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(2L, 0L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Brand")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
    }

    @Test
    void headlinePicksHighestScoringEntityByNegativeWeight() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(1L, 2L));
        // Entity A: 100 mentions, 0 negatives → score = 100
        whatsChangedService.put(USER_ID, 1L, deltaWith(100L, 0L));
        // Entity B: 10 mentions, 30 negatives → score = 30*3+10 = 100  but negatives dominate
        whatsChangedService.put(USER_ID, 2L, deltaWith(10L, 31L));
        when(entityRepository.findById(1L)).thenReturn(Optional.of(entityNamed("Popular")));
        when(entityRepository.findById(2L)).thenReturn(Optional.of(entityNamed("Controversial")));

        service.sendMorningDigests();

        assertThat(emailChannel.calls.get(0).subject).contains("Controversial");
    }

    @Test
    void digestIncludesTopImpactHighlightsCapped() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(3L, 1L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Brand")));
        WorkspaceImpactResponse impact = WorkspaceImpactResponse.builder()
                .highlights(List.of("one", "two", "three", "four", "five"))
                .build();
        workspaceImpactService.put(USER_ID, impact);

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).impactHighlights)
                .containsExactly("one", "two", "three");
    }

    @Test
    void impactFailureDoesNotSinkDigest() {
        MorningDigestService service = serviceAt("2026-05-23T08:00:00Z");
        User user = userWithTimezone("UTC");
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(viewRepository.findEntityIdsByUserId(USER_ID)).thenReturn(List.of(ENTITY_ID));
        whatsChangedService.put(USER_ID, ENTITY_ID, deltaWith(3L, 1L));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entityNamed("Brand")));
        workspaceImpactService.fail(USER_ID, new RuntimeException("boom"));

        service.sendMorningDigests();

        assertThat(emailChannel.calls).hasSize(1);
        assertThat(emailChannel.calls.get(0).impactHighlights).isEmpty();
    }

    private User userWithTimezone(String tz) {
        return userWithTimezone(USER_ID, "testuser", tz);
    }

    private User userWithTimezone(Long id, String username, String tz) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("pass");
        user.setRole("ROLE_USER");
        user.setTimezone(tz);
        return user;
    }

    private WhatsChangedResponse deltaWith(long mentions, long negatives) {
        WhatsChangedResponse r = new WhatsChangedResponse();
        r.setNewMentionsCount(mentions);
        r.setNewNegativeCount(negatives);
        r.setNewSuperSpreaderCount(0L);
        r.setSentimentScoreDelta(0.0);
        return r;
    }

    private ManagedEntity entityNamed(String name) {
        ManagedEntity e = new ManagedEntity();
        e.setName(name);
        return e;
    }

    static class StubWhatsChangedService extends WhatsChangedService {
        private final Map<String, WhatsChangedResponse> results = new HashMap<>();

        StubWhatsChangedService() {
            super(null, null, null, null, null);
        }

        void put(Long userId, Long entityId, WhatsChangedResponse response) {
            results.put(userId + ":" + entityId, response);
        }

        @Override
        public WhatsChangedResponse computeDelta(Long userId, Long entityId) {
            WhatsChangedResponse r = results.get(userId + ":" + entityId);
            return r != null ? r : new WhatsChangedResponse();
        }
    }

    static class StubWorkspaceImpactService extends WorkspaceImpactService {
        private final Map<Long, WorkspaceImpactResponse> byUser = new HashMap<>();
        private final Map<Long, RuntimeException> failByUser = new HashMap<>();

        StubWorkspaceImpactService() {
            super(null, null, null, null, null, null);
        }

        void put(Long userId, WorkspaceImpactResponse response) {
            byUser.put(userId, response);
        }

        void fail(Long userId, RuntimeException error) {
            failByUser.put(userId, error);
        }

        @Override
        public WorkspaceImpactResponse getImpact(Long userId) {
            RuntimeException error = failByUser.get(userId);
            if (error != null) {
                throw error;
            }
            WorkspaceImpactResponse response = byUser.get(userId);
            return response != null
                    ? response
                    : WorkspaceImpactResponse.builder().highlights(List.of()).build();
        }
    }

    static class DigestCall {
        final User user;
        final String subject;
        final Map<String, WhatsChangedResponse> entries;
        final List<String> impactHighlights;

        DigestCall(User user, String subject, Map<String, WhatsChangedResponse> entries,
                   List<String> impactHighlights) {
            this.user = user;
            this.subject = subject;
            this.entries = entries;
            this.impactHighlights = impactHighlights;
        }
    }

    static class RecordingEmailChannel implements EmailChannel {
        final List<DigestCall> calls = new ArrayList<>();

        @Override
        public void send(SentimentAlert alert, String entityName) {
        }

        @Override
        public void sendDigest(User user, String subject, Map<String, WhatsChangedResponse> entries,
                               List<String> impactHighlights) {
            calls.add(new DigestCall(user, subject, entries, impactHighlights));
        }
    }
}
