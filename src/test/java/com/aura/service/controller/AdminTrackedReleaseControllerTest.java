package com.aura.service.controller;

import com.aura.service.dto.TrackedReleaseResponse;
import com.aura.service.dto.UpdateTrackedReleaseRequest;
import com.aura.service.service.TrackedReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTrackedReleaseControllerTest {

    private TrackedReleaseService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TrackedReleaseService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminTrackedReleaseController(service)).build();
    }

    @Test
    void list_defaultsToCurrentScope() throws Exception {
        when(service.list("current")).thenReturn(List.of(TrackedReleaseResponse.builder()
                .id(1L).entityName("Mr Lucky").status("TRACKING").keywords(List.of("MrLucky"))
                .collected(Map.of("x", 3L)).collectedTotal(3).build()));

        mvc.perform(get("/api/admin/tracked-releases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityName").value("Mr Lucky"))
                .andExpect(jsonPath("$[0].status").value("TRACKING"))
                .andExpect(jsonPath("$[0].collectedTotal").value(3));
    }

    @Test
    void patch_passesPartialUpdate() throws Exception {
        when(service.update(eq(7L), any())).thenReturn(TrackedReleaseResponse.builder().id(7L).excluded(true).build());

        mvc.perform(patch("/api/admin/tracked-releases/7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excluded\":true,\"excludedReason\":\"low interest\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excluded").value(true));

        verify(service).update(7L, new UpdateTrackedReleaseRequest(null, true, "low interest", null));
    }

    @Test
    void create_requiresTitleAndDate() throws Exception {
        mvc.perform(post("/api/admin/tracked-releases").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void delete_returnsNoContent() throws Exception {
        mvc.perform(delete("/api/admin/tracked-releases/9")).andExpect(status().isNoContent());
        verify(service).delete(9L);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    @Test
    void addToPlatform_passesOwnerOrNull() throws Exception {
        when(service.addToPlatform(eq(3L), eq(42L))).thenReturn(TrackedReleaseResponse.builder().id(3L).build());
        mvc.perform(post("/api/admin/tracked-releases/3/add-to-platform").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":42}"))
                .andExpect(status().isCreated());
        verify(service).addToPlatform(3L, 42L);

        mvc.perform(post("/api/admin/tracked-releases/3/add-to-platform")).andExpect(status().isCreated());
        verify(service).addToPlatform(3L, null);
    }
}
