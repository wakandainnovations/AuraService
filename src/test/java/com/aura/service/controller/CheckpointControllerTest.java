package com.aura.service.controller;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.CheckpointType;
import com.aura.service.enums.LicenseTier;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.service.CheckpointService;
import com.aura.service.service.EntitlementService;
import com.aura.service.service.EntitlementServiceImpl;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.LicenseService;
import com.aura.service.service.PreviewMaskingServiceImpl;
import com.aura.service.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkpoints is a SILVER feature. These tests build the controller with a real
 * {@link EntitlementServiceImpl} (only interfaces are mocked) so the envelope behavior is exercised
 * end-to-end: an entitled caller (admin here) gets {@code entitled=true} + real {@code data}, an
 * under-tier caller gets {@code entitled=false} + a masked {@code preview} (reads) or a plain locked
 * envelope with the mutation never run (writes).
 */
class CheckpointControllerTest {

    private static final Long ENTITY_ID = 7L;
    private static final String ENTITY_NAME = "Galaxy Quest";

    private CheckpointRepository checkpointRepository;
    private EntityAccessService entityAccess;
    private LicenseService licenseService;
    private CheckpointService service;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        checkpointRepository = mock(CheckpointRepository.class);
        entityAccess = mock(EntityAccessService.class);
        licenseService = mock(LicenseService.class);
        service = new CheckpointService(checkpointRepository, entityAccess);
    }

    /** Builds MockMvc for a caller who is/isn't an admin (the simplest way to flip entitlement). */
    private MockMvc mvcFor(boolean entitledAdmin) {
        when(entityAccess.currentUserIsAdmin()).thenReturn(entitledAdmin);
        EntitlementService entitlement = new EntitlementServiceImpl(
                licenseService, entityAccess, new PreviewMaskingServiceImpl());
        CheckpointController controller = new CheckpointController(service, entitlement);

        MappingJackson2HttpMessageConverter jacksonConverter = new MappingJackson2HttpMessageConverter();
        jacksonConverter.setObjectMapper(mapper);

        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jacksonConverter)
                .build();
    }

    private ManagedEntity entity() {
        ManagedEntity e = new ManagedEntity();
        e.setId(ENTITY_ID);
        e.setName(ENTITY_NAME);
        return e;
    }

    private Checkpoint checkpoint(Long id, LocalDate date, String description) {
        return Checkpoint.builder()
                .id(id)
                .managedEntity(entity())
                .checkpointDate(date)
                .description(description)
                .build();
    }

    // ------------------------------------------------------------------
    // Entitled caller — real data inside the envelope
    // ------------------------------------------------------------------

    @Test
    void create_entitled_savesCheckpointAndReturnsRealData() throws Exception {
        when(entityAccess.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity());
        when(checkpointRepository.save(any(Checkpoint.class))).thenAnswer(inv -> {
            Checkpoint c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        String body = mapper.writeValueAsString(Map.of(
                "entityId", ENTITY_ID,
                "checkpointDate", "2026-06-15",
                "description", "Audio release"));

        mvcFor(true).perform(post("/api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(true))
                .andExpect(jsonPath("$.preview").doesNotExist())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.data.entityName").value(ENTITY_NAME))
                .andExpect(jsonPath("$.data.checkpointDate").value("2026-06-15"))
                .andExpect(jsonPath("$.data.description").value("Audio release"));

        verify(checkpointRepository).save(any(Checkpoint.class));
    }

    @Test
    void create_roundTripsExplicitCheckpointType() throws Exception {
        when(entityAccess.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity());
        when(checkpointRepository.save(any(Checkpoint.class))).thenAnswer(inv -> {
            Checkpoint c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        String body = mapper.writeValueAsString(Map.of(
                "entityId", ENTITY_ID,
                "checkpointDate", "2026-06-15",
                "description", "Trailer drop",
                "checkpointType", "TRAILER"));

        mvcFor(true).perform(post("/api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkpointType").value("TRAILER"));

        ArgumentCaptor<Checkpoint> captor = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCheckpointType())
                .isEqualTo(CheckpointType.TRAILER);
    }

    @Test
    void create_omittingCheckpointType_defaultsToOther() throws Exception {
        when(entityAccess.assertOwnedByCurrentUser(ENTITY_ID)).thenReturn(entity());
        when(checkpointRepository.save(any(Checkpoint.class))).thenAnswer(inv -> {
            Checkpoint c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        String body = mapper.writeValueAsString(Map.of(
                "entityId", ENTITY_ID,
                "checkpointDate", "2026-06-15",
                "description", "Audio release"));

        mvcFor(true).perform(post("/api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkpointType").value("OTHER"));

        ArgumentCaptor<Checkpoint> captor = ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCheckpointType())
                .isEqualTo(CheckpointType.OTHER);
    }

    @Test
    void create_returns400WhenDescriptionExceeds20Chars() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "entityId", ENTITY_ID,
                "checkpointDate", "2026-06-15",
                "description", "This description is way too long"));

        mvcFor(true).perform(post("/api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void create_returns400WhenFieldsMissing() throws Exception {
        String body = mapper.writeValueAsString(Map.of("entityId", ENTITY_ID));

        mvcFor(true).perform(post("/api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void listByEntity_entitled_returnsRealCheckpointsOrderedByDate() throws Exception {
        LocalDate earlier = LocalDate.of(2026, 6, 1);
        LocalDate later = LocalDate.of(2026, 7, 15);
        List<Checkpoint> checkpoints = List.of(
                checkpoint(1L, earlier, "Teaser release"),
                checkpoint(2L, later, "Movie release"));
        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(checkpoints);

        mvcFor(true).perform(get("/api/checkpoints/entity/{entityId}", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(true))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].checkpointDate").value("2026-06-01"))
                .andExpect(jsonPath("$.data[0].description").value("Teaser release"))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[1].checkpointDate").value("2026-07-15"))
                .andExpect(jsonPath("$.data[1].description").value("Movie release"));
    }

    @Test
    void update_entitled_changesDateAndDescription() throws Exception {
        Checkpoint existing = checkpoint(10L, LocalDate.of(2026, 6, 15), "Audio release");
        when(checkpointRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(checkpointRepository.findByManagedEntityIdAndCheckpointDate(ENTITY_ID, LocalDate.of(2026, 7, 1)))
                .thenReturn(Optional.empty());
        when(checkpointRepository.save(any(Checkpoint.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = mapper.writeValueAsString(Map.of(
                "checkpointDate", "2026-07-01",
                "description", "Trailer drop"));

        mvcFor(true).perform(patch("/api/checkpoints/{checkpointId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(true))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.checkpointDate").value("2026-07-01"))
                .andExpect(jsonPath("$.data.description").value("Trailer drop"));

        verify(checkpointRepository).save(existing);
    }

    @Test
    void update_roundTripsCheckpointType() throws Exception {
        Checkpoint existing = checkpoint(10L, LocalDate.of(2026, 6, 15), "Audio release");
        existing.setCheckpointType(CheckpointType.OTHER);
        when(checkpointRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(checkpointRepository.save(any(Checkpoint.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = mapper.writeValueAsString(Map.of("checkpointType", "MUSIC_LAUNCH"));

        mvcFor(true).perform(patch("/api/checkpoints/{checkpointId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkpointType").value("MUSIC_LAUNCH"));

        org.assertj.core.api.Assertions.assertThat(existing.getCheckpointType())
                .isEqualTo(CheckpointType.MUSIC_LAUNCH);
    }

    @Test
    void update_returns400WhenCheckpointNotFound() throws Exception {
        when(checkpointRepository.findById(999L)).thenReturn(Optional.empty());

        String body = mapper.writeValueAsString(Map.of("description", "Re-release"));

        mvcFor(true).perform(patch("/api/checkpoints/{checkpointId}", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void delete_entitled_deletesAndReturnsEntitledEnvelope() throws Exception {
        Checkpoint existing = checkpoint(10L, LocalDate.of(2026, 6, 15), "Audio release");
        when(checkpointRepository.findById(10L)).thenReturn(Optional.of(existing));

        mvcFor(true).perform(delete("/api/checkpoints/{checkpointId}", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(true));

        verify(checkpointRepository).delete(existing);
    }

    // ------------------------------------------------------------------
    // Unentitled caller — masked preview (read) / locked, action never runs (write)
    // ------------------------------------------------------------------

    @Test
    void listByEntity_unentitled_returnsMaskedPreviewWithNoRealValues() throws Exception {
        when(entityAccess.currentUserIsAdmin()).thenReturn(false);
        when(licenseService.effectiveTier()).thenReturn(LicenseTier.BRONZE); // below SILVER
        List<Checkpoint> checkpoints = List.of(
                checkpoint(1L, LocalDate.of(2026, 6, 1), "Teaser release"),
                checkpoint(2L, LocalDate.of(2026, 7, 15), "Movie release"));
        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(checkpoints);

        String response = mvcFor(false).perform(get("/api/checkpoints/entity/{entityId}", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(false))
                .andExpect(jsonPath("$.requiredTier").value("SILVER"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.preview").exists())
                .andReturn().getResponse().getContentAsString();

        // No real underlying value may leak into the preview.
        org.assertj.core.api.Assertions.assertThat(response)
                .doesNotContain("Teaser release")
                .doesNotContain("Movie release")
                .doesNotContain(ENTITY_NAME)
                .doesNotContain("2026-06-01")
                .doesNotContain("2026-07-15");
        // List is truncated to a teaser (1 element), not the full 2.
        org.assertj.core.api.Assertions.assertThat(
                mapper.readTree(response).get("preview").size()).isEqualTo(1);
    }

    @Test
    void create_unentitled_isLockedAndNeverSaves() throws Exception {
        when(entityAccess.currentUserIsAdmin()).thenReturn(false);
        when(licenseService.effectiveTier()).thenReturn(LicenseTier.BRONZE);

        String body = mapper.writeValueAsString(Map.of(
                "entityId", ENTITY_ID,
                "checkpointDate", "2026-06-15",
                "description", "Audio release"));

        mvcFor(false).perform(post("/api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(false))
                .andExpect(jsonPath("$.requiredTier").value("SILVER"))
                .andExpect(jsonPath("$.data").doesNotExist());

        // The mutation must NOT run for an unentitled caller.
        verify(checkpointRepository, never()).save(any());
        verify(entityAccess, never()).assertOwnedByCurrentUser(any());
    }
}
