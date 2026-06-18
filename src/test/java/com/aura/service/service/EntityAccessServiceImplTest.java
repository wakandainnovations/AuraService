package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.User;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the admin bypass / view-scoping rules in {@link EntityAccessServiceImpl}, with the
 * repositories mocked as interfaces (not concrete classes). Covers: an admin reaching any user's
 * entity, an admin pinned to a specific {@code ownerId}, missing entities still 404-ing for admins,
 * and a non-admin being rejected (403) the moment they supply an {@code ownerId}.
 */
class EntityAccessServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 2L;
    private static final Long OTHER_ID = 3L;

    private ManagedEntityRepository entityRepository;
    private UserRepository userRepository;
    private EntityAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        userRepository = mock(UserRepository.class);
        service = new EntityAccessServiceImpl(entityRepository, userRepository);

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(USER_ID, "alice", "ROLE_USER")));
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(user(ADMIN_ID, "root", "ROLE_ADMIN")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "x", List.of()));
    }

    private User user(Long id, String username, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("x");
        u.setRole(role);
        return u;
    }

    private ManagedEntity entityOwnedBy(Long id, Long ownerId) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName("The Quantum Paradox");
        e.setType("MOVIE");
        e.setOwner(user(ownerId, "owner-" + ownerId, "ROLE_USER"));
        return e;
    }

    // ------------------------------------------------------------------
    // currentUserIsAdmin
    // ------------------------------------------------------------------

    @Test
    void currentUserIsAdmin_reflectsRole() {
        authenticateAs("alice");
        assertThat(service.currentUserIsAdmin()).isFalse();

        authenticateAs("root");
        assertThat(service.currentUserIsAdmin()).isTrue();
    }

    // ------------------------------------------------------------------
    // Admin bypass on the plain ownership guard.
    // ------------------------------------------------------------------

    @Test
    void assertOwnedByCurrentUser_adminReachesAnotherUsersEntity() {
        authenticateAs("root");
        when(entityRepository.findById(5L)).thenReturn(Optional.of(entityOwnedBy(5L, OTHER_ID)));

        ManagedEntity result = service.assertOwnedByCurrentUser(5L);

        assertThat(result.getId()).isEqualTo(5L);
    }

    @Test
    void assertOwnedByCurrentUser_adminStill404sOnMissingEntity() {
        authenticateAs("root");
        when(entityRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertOwnedByCurrentUser(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assertOwnedByCurrentUser_nonAdminStillBlockedFromOthersEntity() {
        authenticateAs("alice");
        when(entityRepository.findById(5L)).thenReturn(Optional.of(entityOwnedBy(5L, OTHER_ID)));

        assertThatThrownBy(() -> service.assertOwnedByCurrentUser(5L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // assertAccessible with an admin ownerId scope.
    // ------------------------------------------------------------------

    @Test
    void assertAccessible_adminScopedToOwner_returnsMatchingEntity() {
        authenticateAs("root");
        when(entityRepository.findById(5L)).thenReturn(Optional.of(entityOwnedBy(5L, OTHER_ID)));

        ManagedEntity result = service.assertAccessible(5L, OTHER_ID);

        assertThat(result.getId()).isEqualTo(5L);
    }

    @Test
    void assertAccessible_adminScopedToOwner_404sOnMismatch() {
        authenticateAs("root");
        when(entityRepository.findById(5L)).thenReturn(Optional.of(entityOwnedBy(5L, OTHER_ID)));

        // Admin scoped to USER_ID must not reach an entity owned by OTHER_ID.
        assertThatThrownBy(() -> service.assertAccessible(5L, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assertAccessible_nonAdminWithOwnerId_isForbiddenBeforeLookup() {
        authenticateAs("alice");

        assertThatThrownBy(() -> service.assertAccessible(5L, OTHER_ID))
                .isInstanceOf(AccessDeniedException.class);

        // Rejected before the entity is ever loaded — no existence probing via ownerId.
        verify(entityRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    // ------------------------------------------------------------------
    // resolveOwnerScope (listing filter).
    // ------------------------------------------------------------------

    @Test
    void resolveOwnerScope_nonAdminPinnedToSelf() {
        authenticateAs("alice");
        assertThat(service.resolveOwnerScope(null)).isEqualTo(USER_ID);
    }

    @Test
    void resolveOwnerScope_nonAdminWithOwnerId_isForbidden() {
        authenticateAs("alice");
        assertThatThrownBy(() -> service.resolveOwnerScope(OTHER_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void resolveOwnerScope_adminUnscopedMeansAll() {
        authenticateAs("root");
        assertThat(service.resolveOwnerScope(null)).isNull();
    }

    @Test
    void resolveOwnerScope_adminScopedToRequestedOwner() {
        authenticateAs("root");
        assertThat(service.resolveOwnerScope(OTHER_ID)).isEqualTo(OTHER_ID);
    }

    @Test
    void requireAdminToScopeByOwner_allowsNullForEveryone() {
        authenticateAs("alice");
        service.requireAdminToScopeByOwner(null); // no exception
    }

    // ------------------------------------------------------------------
    // assertMentionAccessible: a post may be attributed to several entities, so the guard returns the
    // link the caller is allowed to act through rather than assuming a single entity.
    // ------------------------------------------------------------------

    private Mention mentionLinkedTo(ManagedEntity... entities) {
        Mention m = new Mention();
        m.setId(99L);
        for (ManagedEntity e : entities) {
            m.addManagedEntity(e);
        }
        return m;
    }

    @Test
    void assertMentionAccessible_nonAdminReturnsTheLinkTheyOwn() {
        authenticateAs("alice");
        // The post is shared between another user's entity and alice's — she may act through hers.
        Mention mention = mentionLinkedTo(entityOwnedBy(5L, OTHER_ID), entityOwnedBy(6L, USER_ID));

        ManagedEntity result = service.assertMentionAccessible(mention);

        assertThat(result.getId()).isEqualTo(6L);
    }

    @Test
    void assertMentionAccessible_nonAdmin404sWhenOwningNoLinkedEntity() {
        authenticateAs("alice");
        Mention mention = mentionLinkedTo(entityOwnedBy(5L, OTHER_ID));

        assertThatThrownBy(() -> service.assertMentionAccessible(mention))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assertMentionAccessible_adminUnscopedReachesAnyLink() {
        authenticateAs("root");
        Mention mention = mentionLinkedTo(entityOwnedBy(5L, OTHER_ID));

        ManagedEntity result = service.assertMentionAccessible(mention);

        assertThat(result.getId()).isEqualTo(5L);
    }

    @Test
    void assertMentionAccessible_adminScopedToOwnerReturnsThatOwnersLink() {
        authenticateAs("root");
        Mention mention = mentionLinkedTo(entityOwnedBy(5L, USER_ID), entityOwnedBy(6L, OTHER_ID));

        ManagedEntity result = service.assertMentionAccessible(mention, OTHER_ID);

        assertThat(result.getId()).isEqualTo(6L);
    }

    @Test
    void assertMentionAccessible_nonAdminWithOwnerId_isForbidden() {
        authenticateAs("alice");
        Mention mention = mentionLinkedTo(entityOwnedBy(6L, USER_ID));

        assertThatThrownBy(() -> service.assertMentionAccessible(mention, OTHER_ID))
                .isInstanceOf(AccessDeniedException.class);
    }
}
