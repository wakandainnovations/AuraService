package com.aura.service.controller;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.EntityAccessServiceImpl;
import com.aura.service.service.EntityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end ownership enforcement: a user may only read/update/delete entities they own, and
 * listing is owner-scoped. Exercises the real {@link EntityAccessServiceImpl} (resolving the caller
 * from the security context) wired through {@link EntityService} and {@link EntityController}, so the
 * full chain — guard, exception, and the 404 mapping — is covered.
 */
class EntityOwnershipTest {

    private static final String CALLER = "alice";
    private static final Long CALLER_ID = 1L;
    private static final Long OTHER_ID = 2L;

    private ManagedEntityRepository entityRepository;
    private UserRepository userRepository;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        userRepository = mock(UserRepository.class);
        CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
        MentionRepository mentionRepository = mock(MentionRepository.class);

        EntityAccessServiceImpl entityAccess =
                new EntityAccessServiceImpl(entityRepository, userRepository);
        EntityService service = new EntityService(
                entityRepository, checkpointRepository, mentionRepository, entityAccess);
        EntityController controller = new EntityController(service);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // Authenticate as "alice" for the duration of the test.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, "x", List.of()));
        when(userRepository.findByUsername(CALLER)).thenReturn(Optional.of(user(CALLER_ID, CALLER)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User user(Long id, String username) {
        return user(id, username, "ROLE_USER");
    }

    private User user(Long id, String username, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("x");
        u.setRole(role);
        return u;
    }

    /** Re-authenticate the security context as an admin ("root") for the admin-path tests. */
    private void authenticateAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("root", "x", List.of()));
        when(userRepository.findByUsername("root"))
                .thenReturn(Optional.of(user(99L, "root", "ROLE_ADMIN")));
    }

    private ManagedEntity ownedBy(Long id, Long ownerId) {
        ManagedEntity e = new ManagedEntity();
        e.setId(id);
        e.setName("The Quantum Paradox");
        e.setType("MOVIE");
        e.setOwner(user(ownerId, ownerId.equals(CALLER_ID) ? CALLER : "bob"));
        return e;
    }

    // ------------------------------------------------------------------
    // A user cannot read / update / delete another user's entity (404, not 403).
    // ------------------------------------------------------------------

    @Test
    void get_returns404ForEntityOwnedByAnotherUser() throws Exception {
        when(entityRepository.findById(5L)).thenReturn(Optional.of(ownedBy(5L, OTHER_ID)));

        mvc.perform(get("/api/entities/{type}/{id}", "movie", 5L))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_returns404ForEntityOwnedByAnotherUser() throws Exception {
        when(entityRepository.findById(5L)).thenReturn(Optional.of(ownedBy(5L, OTHER_ID)));

        String body = mapper.writeValueAsString(Map.of("name", "New Name"));
        mvc.perform(put("/api/entities/{type}/{id}", "movie", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        verify(entityRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void delete_returns404ForEntityOwnedByAnotherUser() throws Exception {
        when(entityRepository.findById(5L)).thenReturn(Optional.of(ownedBy(5L, OTHER_ID)));

        mvc.perform(delete("/api/entities/{type}/{id}", "movie", 5L))
                .andExpect(status().isNotFound());

        verify(entityRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void get_returns404ForMissingEntity_indistinguishableFromNotOwned() throws Exception {
        when(entityRepository.findById(404L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/entities/{type}/{id}", "movie", 404L))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // A user can act on their own entity.
    // ------------------------------------------------------------------

    @Test
    void get_returns200ForOwnedEntity() throws Exception {
        when(entityRepository.findById(7L)).thenReturn(Optional.of(ownedBy(7L, CALLER_ID)));

        mvc.perform(get("/api/entities/{type}/{id}", "movie", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    // ------------------------------------------------------------------
    // Listing is owner-scoped.
    // ------------------------------------------------------------------

    @Test
    void list_isScopedToCallerAndNeverQueriesGlobally() throws Exception {
        when(entityRepository.findByTypeAndOwnerId("MOVIE", CALLER_ID))
                .thenReturn(List.of(ownedBy(7L, CALLER_ID)));

        mvc.perform(get("/api/entities/{type}", "movie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(7));

        // The owner-scoped query is used; the global by-type query must never be touched.
        verify(entityRepository).findByTypeAndOwnerId("MOVIE", CALLER_ID);
        verify(entityRepository, never()).findByType(anyString());
    }

    // ------------------------------------------------------------------
    // Admin access: all entities by default, scoped by ownerId, and non-admins rejected.
    // ------------------------------------------------------------------

    @Test
    void list_adminWithoutOwnerId_seesAllEntitiesGlobally() throws Exception {
        authenticateAsAdmin();
        when(entityRepository.findByType("MOVIE"))
                .thenReturn(List.of(ownedBy(7L, CALLER_ID), ownedBy(8L, OTHER_ID)));

        mvc.perform(get("/api/entities/{type}", "movie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // An unscoped admin lists across all owners — the global query is used, not the owner-scoped one.
        verify(entityRepository).findByType("MOVIE");
        verify(entityRepository, never()).findByTypeAndOwnerId(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void list_adminWithOwnerId_scopesToThatUser() throws Exception {
        authenticateAsAdmin();
        when(entityRepository.findByTypeAndOwnerId("MOVIE", OTHER_ID))
                .thenReturn(List.of(ownedBy(8L, OTHER_ID)));

        mvc.perform(get("/api/entities/{type}", "movie").param("ownerId", OTHER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(8));

        verify(entityRepository).findByTypeAndOwnerId("MOVIE", OTHER_ID);
        verify(entityRepository, never()).findByType(anyString());
    }

    @Test
    void list_nonAdminPassingOwnerId_isForbidden() throws Exception {
        // Caller "alice" is a ROLE_USER (set up in @BeforeEach).
        mvc.perform(get("/api/entities/{type}", "movie").param("ownerId", OTHER_ID.toString()))
                .andExpect(status().isForbidden());

        // Rejected before any listing query runs.
        verify(entityRepository, never()).findByType(anyString());
        verify(entityRepository, never()).findByTypeAndOwnerId(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
