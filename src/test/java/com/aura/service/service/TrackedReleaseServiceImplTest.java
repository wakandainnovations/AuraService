package com.aura.service.service;

import com.aura.service.dto.CreateTrackedReleaseRequest;
import com.aura.service.dto.TrackedReleaseResponse;
import com.aura.service.dto.UpdateTrackedReleaseRequest;
import com.aura.service.entity.TrackedRelease;
import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.KeywordDto;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.TrackedReleaseRepository;
import com.aura.service.repository.UserRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrackedReleaseServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private TrackedReleaseRepository repository;
    private EntityAccessService access;
    private NamedParameterJdbcTemplate jdbc;
    private TrackedReleaseServiceImpl service;
    private ManagedEntityRepository entities;
    private UserRepository users;
    private EntityService entityService;

    @BeforeEach
    void setUp() {
        repository = mock(TrackedReleaseRepository.class);
        access = mock(EntityAccessService.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        when(access.currentUserIsAdmin()).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        entities = mock(ManagedEntityRepository.class);
        users = mock(UserRepository.class);
        entityService = mock(EntityService.class);
        service = new TrackedReleaseServiceImpl(repository, access, jdbc, entities, users, entityService);
        service.setClock(Clock.fixed(TODAY.atStartOfDay(ZoneId.of("Asia/Kolkata")).plusHours(12).toInstant(),
                ZoneId.of("Asia/Kolkata")));
    }

    private static TrackedRelease release(long id, String name, LocalDate releaseDate, boolean excluded, String source) {
        TrackedRelease r = new TrackedRelease();
        r.setId(id);
        r.setEntityName(name);
        r.setTitle(name);
        r.setReleaseDate(releaseDate);
        r.setTrackFrom(releaseDate.minusDays(7));
        r.setTrackUntil(releaseDate.plusDays(7));
        r.setKeywords(name.replace(" ", "") + "," + name);
        r.setExcluded(excluded);
        r.setSource(source);
        return r;
    }

    @Test
    void status_followsTheSevenDayWindow() {
        assertThat(TrackedReleaseServiceImpl.status(release(1, "A", TODAY.plusDays(8), false, "TMDB"), TODAY)).isEqualTo("UPCOMING");
        assertThat(TrackedReleaseServiceImpl.status(release(1, "A", TODAY.plusDays(7), false, "TMDB"), TODAY)).isEqualTo("TRACKING");
        assertThat(TrackedReleaseServiceImpl.status(release(1, "A", TODAY.minusDays(7), false, "TMDB"), TODAY)).isEqualTo("TRACKING");
        assertThat(TrackedReleaseServiceImpl.status(release(1, "A", TODAY.minusDays(8), false, "TMDB"), TODAY)).isEqualTo("ENDED");
        assertThat(TrackedReleaseServiceImpl.status(release(1, "A", TODAY, true, "TMDB"), TODAY)).isEqualTo("EXCLUDED");
    }

    @Test
    void list_scopesFilterRows() {
        when(repository.findAllByOrderByReleaseDateAscEntityNameAsc()).thenReturn(List.of(
                release(1, "Upcoming", TODAY.plusDays(10), false, "TMDB"),
                release(2, "Live", TODAY, false, "TMDB"),
                release(3, "Old", TODAY.minusDays(20), false, "TMDB"),
                release(4, "Dropped", TODAY, true, "TMDB")));

        assertThat(service.list("current")).extracting(TrackedReleaseResponse::getEntityName).containsExactly("Upcoming", "Live");
        assertThat(service.list("excluded")).extracting(TrackedReleaseResponse::getEntityName).containsExactly("Dropped");
        assertThat(service.list("ended")).extracting(TrackedReleaseResponse::getEntityName).containsExactly("Old");
        assertThat(service.list("all")).hasSize(4);
        assertThatThrownBy(() -> service.list("bogus")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void list_reportsCollectedCountsAndSurvivesMissingTables() throws Exception {
        when(repository.findAllByOrderByReleaseDateAscEntityNameAsc()).thenReturn(List.of(release(2, "Live", TODAY, false, "TMDB")));
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("instagram_posts")) throw new RuntimeException("relation does not exist");
            if (sql.contains("FROM x_posts")) {
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getString("entity")).thenReturn("Live");
                when(rs.getLong("n")).thenReturn(42L);
                ((RowCallbackHandler) inv.getArgument(2)).processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));

        TrackedReleaseResponse r = service.list("current").get(0);
        assertThat(r.getCollected()).containsEntry("x", 42L).containsEntry("instagram", 0L);
        assertThat(r.getCollectedTotal()).isEqualTo(42L);
        assertThat(r.getKeywords()).containsExactly("Live");
        assertThat(r.getRelevancePct()).isNull();
        assertThat(r.isRelevanceChecked()).isFalse();
    }

    @Test
    void update_excludeAndRestore() {
        TrackedRelease r = release(5, "Big Movie", TODAY, false, "TMDB");
        when(repository.findById(5L)).thenReturn(Optional.of(r));

        TrackedReleaseResponse excluded = service.update(5L, new UpdateTrackedReleaseRequest(null, true, "  too noisy ", null));
        assertThat(excluded.isExcluded()).isTrue();
        assertThat(excluded.getExcludedReason()).isEqualTo("too noisy");
        assertThat(excluded.getExcludedAt()).isNotNull();
        assertThat(excluded.getStatus()).isEqualTo("EXCLUDED");

        assertThat(excluded.isRelevanceOverride()).isFalse();

        TrackedReleaseResponse restored = service.update(5L, new UpdateTrackedReleaseRequest(null, false, null, null));
        assertThat(restored.isExcluded()).isFalse();
        // Restoring exempts the movie from the collector's relevance auto-exclusion.
        assertThat(restored.isRelevanceOverride()).isTrue();
        assertThat(restored.getExcludedReason()).isNull();
        assertThat(restored.getStatus()).isEqualTo("TRACKING");
    }

    @Test
    void update_keywordsAreNormalizedAndMustNotBeEmpty() {
        TrackedRelease r = release(5, "Toxic", TODAY, false, "TMDB");
        when(repository.findById(5L)).thenReturn(Optional.of(r));

        service.update(5L, new UpdateTrackedReleaseRequest(List.of(" ToxicTheMovie ", "toxicthemovie", "Toxic Yash, YashToxic", ""), null, null, null));
        assertThat(r.getKeywords()).isEqualTo("ToxicTheMovie,Toxic Yash,YashToxic");

        assertThatThrownBy(() -> service.update(5L, new UpdateTrackedReleaseRequest(List.of(" "), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_releaseDateOnlyEditableForManualRows() {
        TrackedRelease tmdb = release(5, "A", TODAY, false, "TMDB");
        TrackedRelease manual = release(6, "B", TODAY, false, "MANUAL");
        when(repository.findById(5L)).thenReturn(Optional.of(tmdb));
        when(repository.findById(6L)).thenReturn(Optional.of(manual));

        assertThatThrownBy(() -> service.update(5L, new UpdateTrackedReleaseRequest(null, null, null, TODAY.plusDays(30))))
                .isInstanceOf(IllegalArgumentException.class);

        service.update(6L, new UpdateTrackedReleaseRequest(null, null, null, TODAY.plusDays(30)));
        assertThat(manual.getTrackFrom()).isEqualTo(TODAY.plusDays(23));
        assertThat(manual.getTrackUntil()).isEqualTo(TODAY.plusDays(37));
    }

    @Test
    void create_manualRowGetsWindowAndDefaultKeywords() {
        TrackedReleaseResponse r = service.create(new CreateTrackedReleaseRequest(" Appu Garage ", TODAY.plusDays(3), "Kannada", null));
        assertThat(r.getEntityName()).isEqualTo("Appu Garage");
        assertThat(r.getSource()).isEqualTo("MANUAL");
        assertThat(r.getLanguage()).isEqualTo("kannada");
        assertThat(r.getTrackFrom()).isEqualTo(TODAY.minusDays(4));
        assertThat(r.getTrackUntil()).isEqualTo(TODAY.plusDays(10));
        assertThat(r.getKeywords()).containsExactly("AppuGarage", "Appu Garage");
        assertThat(r.getStatus()).isEqualTo("TRACKING");
    }

    @Test
    void create_rejectsDuplicateEntity() {
        when(repository.existsByEntityNameIgnoreCase("Mr Lucky")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateTrackedReleaseRequest("Mr Lucky", TODAY, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_onlyManualRows() {
        when(repository.findById(5L)).thenReturn(Optional.of(release(5, "A", TODAY, false, "TMDB")));
        TrackedRelease manual = release(6, "B", TODAY, false, "MANUAL");
        when(repository.findById(6L)).thenReturn(Optional.of(manual));

        assertThatThrownBy(() -> service.delete(5L)).isInstanceOf(IllegalArgumentException.class);
        service.delete(6L);
        verify(repository).delete(manual);
    }

    @Test
    void nonAdminIsRejected() {
        when(access.currentUserIsAdmin()).thenReturn(false);
        assertThatThrownBy(() -> service.list("current")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc);
        assertThat(Map.of()).isEmpty();
    }

    private static User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        return u;
    }

    @Test
    void addToPlatform_createsMovieForChosenOwnerWithSameNameAndKeywords() {
        TrackedRelease r = release(7, "Itllu Arjuna", TODAY, false, "TMDB");
        r.setLanguage("telugu");
        r.setKeywords("ItlluArjuna,Itllu Arjuna");
        when(repository.findById(7L)).thenReturn(Optional.of(r));
        User owner = user(42, "producer1");
        when(users.findById(42L)).thenReturn(Optional.of(owner));
        ManagedEntity created = new ManagedEntity();
        created.setId(900L);
        created.setName("Itllu Arjuna");
        created.setOwner(owner);
        when(entities.findByType("MOVIE")).thenReturn(List.of(created));

        TrackedReleaseResponse resp = service.addToPlatform(7L, 42L);

        ArgumentCaptor<CreateEntityRequest> req = ArgumentCaptor.forClass(CreateEntityRequest.class);
        verify(entityService).createEntityForOwner(eq(owner), eq("MOVIE"), req.capture());
        assertThat(req.getValue().getName()).isEqualTo("Itllu Arjuna");
        assertThat(req.getValue().getReleaseDate()).isEqualTo(TODAY);
        assertThat(req.getValue().getLanguage()).isEqualTo("Telugu");
        assertThat(req.getValue().getIndustry()).isEqualTo("Tollywood");
        assertThat(req.getValue().getKeywords()).extracting(KeywordDto::getKeyword).containsExactly("ItlluArjuna", "Itllu Arjuna");
        assertThat(resp.getPlatformEntities()).singleElement()
                .satisfies(p -> {
                    assertThat(p.entityId()).isEqualTo(900L);
                    assertThat(p.ownerUsername()).isEqualTo("producer1");
                });
    }

    @Test
    void addToPlatform_defaultsToCallingAdminAndRejectsDuplicates() {
        TrackedRelease r = release(7, "Itllu Arjuna", TODAY, false, "TMDB");
        when(repository.findById(7L)).thenReturn(Optional.of(r));
        User admin = user(1, "admin");
        when(access.currentUser()).thenReturn(admin);
        when(entities.findByTypeAndNameIgnoreCaseAndOwnerId("MOVIE", "Itllu Arjuna", 1L)).thenReturn(List.of(new ManagedEntity()));

        assertThatThrownBy(() -> service.addToPlatform(7L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already on the platform for admin");
        verifyNoInteractions(entityService);
    }

    @Test
    void list_showsWhichUsersAlreadyHaveTheMovie() {
        when(repository.findAllByOrderByReleaseDateAscEntityNameAsc()).thenReturn(List.of(release(2, "Live", TODAY, false, "TMDB")));
        ManagedEntity e = new ManagedEntity();
        e.setId(5L);
        e.setName("live");
        e.setOwner(user(3, "bob"));
        when(entities.findByType("MOVIE")).thenReturn(List.of(e));

        assertThat(service.list("current").get(0).getPlatformEntities()).extracting(p -> p.ownerUsername()).containsExactly("bob");
    }
}
