package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.TrackedRelease;
import com.aura.service.entity.User;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.TrackedReleaseRepository;
import com.aura.service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrackedReleaseAutoAddServiceTest {

    private TrackedReleaseRepository releases;
    private ManagedEntityRepository entities;
    private UserRepository users;
    private EntityService entityService;
    private TrackedReleaseAutoAddService service;
    private User bahubali;

    @BeforeEach
    void setUp() {
        releases = mock(TrackedReleaseRepository.class);
        entities = mock(ManagedEntityRepository.class);
        users = mock(UserRepository.class);
        entityService = mock(EntityService.class);
        service = new TrackedReleaseAutoAddService(releases, entities, users, entityService);
        bahubali = new User();
        bahubali.setId(50L);
        bahubali.setUsername("Bahubali");
        when(users.findByUsername("Bahubali")).thenReturn(Optional.of(bahubali));
        when(entities.findByTypeAndNameIgnoreCase(eq("MOVIE"), anyString())).thenReturn(List.of());
    }

    private static TrackedRelease release(String name, boolean excluded) {
        TrackedRelease r = new TrackedRelease();
        r.setEntityName(name);
        r.setTitle(name);
        r.setLanguage("telugu");
        LocalDate d = LocalDate.of(2026, 11, 20);
        r.setReleaseDate(d);
        r.setTrackFrom(d.minusDays(7));
        r.setTrackUntil(d.plusDays(7));
        r.setKeywords(name.replace(" ", ""));
        r.setExcluded(excluded);
        return r;
    }

    @Test
    void addsEveryTrackedReleaseNotYetOnThePlatform_ownedByTheConfiguredUser() {
        when(releases.findAllByOrderByReleaseDateAscEntityNameAsc())
                .thenReturn(List.of(release("Movie One", false), release("Movie Two", false)));

        TrackedReleaseAutoAddService.Result result = service.addAllToPlatform();

        ArgumentCaptor<CreateEntityRequest> req = ArgumentCaptor.forClass(CreateEntityRequest.class);
        verify(entityService, times(2)).createEntityForOwnerIgnoringLimits(same(bahubali), eq("MOVIE"), req.capture());
        assertThat(req.getAllValues()).extracting(CreateEntityRequest::getName).containsExactly("Movie One", "Movie Two");
        assertThat(req.getAllValues().get(0).getIndustry()).isEqualTo("Tollywood");
        assertThat(result.added()).isEqualTo(2);
    }

    @Test
    void skipsExcludedReleasesAndOnesAlreadyOnThePlatformForAnyOwner() {
        when(releases.findAllByOrderByReleaseDateAscEntityNameAsc())
                .thenReturn(List.of(release("Excluded", true), release("Already There", false)));
        when(entities.findByTypeAndNameIgnoreCase("MOVIE", "Already There")).thenReturn(List.of(new ManagedEntity()));

        TrackedReleaseAutoAddService.Result result = service.addAllToPlatform();

        verifyNoInteractions(entityService);
        assertThat(result.excluded()).isEqualTo(1);
        assertThat(result.alreadyOnPlatform()).isEqualTo(1);
    }

    @Test
    void neverUsesTheLicenseCappedPath() {
        when(releases.findAllByOrderByReleaseDateAscEntityNameAsc()).thenReturn(List.of(release("Movie One", false)));

        service.addAllToPlatform();

        verify(entityService, never()).createEntityForOwner(any(), any(), any());
    }

    @Test
    void aFailureOnOneReleaseDoesNotStopTheOthers() {
        when(releases.findAllByOrderByReleaseDateAscEntityNameAsc())
                .thenReturn(List.of(release("Broken", false), release("Fits", false)));
        when(entityService.createEntityForOwnerIgnoringLimits(any(), eq("MOVIE"), argThat(r -> r != null && "Broken".equals(r.getName()))))
                .thenThrow(new IllegalStateException("boom"));

        TrackedReleaseAutoAddService.Result result = service.addAllToPlatform();

        assertThat(result.added()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void doesNothingWhenTheOwnerDoesNotExist() {
        service.setOwnerUsername("nobody");
        when(users.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThat(service.addAllToPlatform().added()).isZero();
        verifyNoInteractions(entityService, releases);
    }
}
