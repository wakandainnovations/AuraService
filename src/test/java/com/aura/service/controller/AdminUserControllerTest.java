package com.aura.service.controller;

import com.aura.service.entity.User;
import com.aura.service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin user directory returns only id + username (never the password hash), ordered by
 * username, for the UI's view-scoping selector. (ROLE_ADMIN enforcement lives in the security
 * filter chain / {@code @PreAuthorize}; this standalone test covers the projection and ordering.)
 */
class AdminUserControllerTest {

    private UserRepository userRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        AdminUserController controller = new AdminUserController(userRepository);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private User user(Long id, String username, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("super-secret-hash");
        u.setRole(role);
        return u;
    }

    @Test
    void listUsers_returnsIdAndUsernameOnly_sortedByUsername() throws Exception {
        when(userRepository.findAll()).thenReturn(List.of(
                user(1L, "user", "ROLE_USER"),
                user(2L, "admin", "ROLE_ADMIN")));

        mvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Sorted case-insensitively by username: "admin" before "user".
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[1].id").value(1))
                .andExpect(jsonPath("$[1].username").value("user"))
                // The password hash must never be serialized.
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[1].password").doesNotExist());
    }
}
