package com.aura.service.config;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntityOwnerBackfillTest {

    private final ManagedEntityRepository entityRepository = mock(ManagedEntityRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final EntityOwnerBackfill backfill = new EntityOwnerBackfill(entityRepository, userRepository);

    private User admin(Long id) {
        User u = new User();
        u.setId(id);
        u.setUsername("admin");
        u.setRole("ROLE_ADMIN");
        return u;
    }

    private ManagedEntity orphan(Long id) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName("Legacy " + id);
        e.setType("MOVIE");
        return e;
    }

    @Test
    void assignsAdminToEveryOwnerlessEntity() {
        User admin = admin(99L);
        ManagedEntity a = orphan(1L);
        ManagedEntity b = orphan(2L);
        when(entityRepository.findByOwnerIsNull()).thenReturn(List.of(a, b));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        backfill.run(null);

        assertThat(a.getOwner()).isSameAs(admin);
        assertThat(b.getOwner()).isSameAs(admin);
        verify(entityRepository).saveAll(List.of(a, b));
    }

    @Test
    void isNoOpWhenNoOrphanedEntities() {
        when(entityRepository.findByOwnerIsNull()).thenReturn(List.of());

        backfill.run(null);

        verify(userRepository, never()).findByUsername(any());
        verify(entityRepository, never()).saveAll(any());
    }

    @Test
    void skipsWhenNoAdminUser() {
        ManagedEntity a = orphan(1L);
        when(entityRepository.findByOwnerIsNull()).thenReturn(List.of(a));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());

        backfill.run(null);

        assertThat(a.getOwner()).isNull();
        verify(entityRepository, never()).saveAll(any());
    }
}
