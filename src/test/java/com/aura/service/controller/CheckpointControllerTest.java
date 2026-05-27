package com.aura.service.controller;

import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.service.CheckpointService;
import com.aura.service.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckpointControllerTest {

    private static final Long ENTITY_ID = 7L;
    private static final String ENTITY_NAME = "Galaxy Quest";

    private CheckpointRepository checkpointRepository;
    private ManagedEntityRepository entityRepository;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        checkpointRepository = mock(CheckpointRepository.class);
        entityRepository = mock(ManagedEntityRepository.class);

        CheckpointService service = new CheckpointService(checkpointRepository, entityRepository);
        CheckpointController controller = new CheckpointController(service);

        MappingJackson2HttpMessageConverter jacksonConverter = new MappingJackson2HttpMessageConverter();
        jacksonConverter.setObjectMapper(mapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
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

    @Test
    void create_savesCheckpointAndReturns201() throws Exception {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity()));
        when(checkpointRepository.save(any(Checkpoint.class))).thenAnswer(inv -> {
            Checkpoint c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        String body = mapper.writeValueAsString(Map.of(
                "entityId", ENTITY_ID,
                "checkpointDate", "2026-06-15",
                "description", "Audio release"));

        mvc.perform(post("/api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.entityName").value(ENTITY_NAME))
                .andExpect(jsonPath("$.checkpointDate").value("2026-06-15"))
                .andExpect(jsonPath("$.description").value("Audio release"));

        verify(checkpointRepository).save(any(Checkpoint.class));
    }

    @Test
    void create_returns400WhenDescriptionExceeds20Chars() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "entityId", ENTITY_ID,
                "checkpointDate", "2026-06-15",
                "description", "This description is way too long"));

        mvc.perform(post("/api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void create_returns400WhenFieldsMissing() throws Exception {
        String body = mapper.writeValueAsString(Map.of("entityId", ENTITY_ID));

        mvc.perform(post("/api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(checkpointRepository, never()).save(any());
    }

    @Test
    void listByEntity_returnsCheckpointsOrderedByDate() throws Exception {
        LocalDate earlier = LocalDate.of(2026, 6, 1);
        LocalDate later = LocalDate.of(2026, 7, 15);
        List<Checkpoint> checkpoints = List.of(
                checkpoint(1L, earlier, "Teaser release"),
                checkpoint(2L, later, "Movie release"));
        when(checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(ENTITY_ID))
                .thenReturn(checkpoints);

        mvc.perform(get("/api/checkpoints/entity/{entityId}", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].checkpointDate").value("2026-06-01"))
                .andExpect(jsonPath("$[0].description").value("Teaser release"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].checkpointDate").value("2026-07-15"))
                .andExpect(jsonPath("$[1].description").value("Movie release"));
    }

    @Test
    void delete_returns204WhenCheckpointExists() throws Exception {
        Checkpoint existing = checkpoint(10L, LocalDate.of(2026, 6, 15), "Audio release");
        when(checkpointRepository.findById(10L)).thenReturn(Optional.of(existing));

        mvc.perform(delete("/api/checkpoints/{checkpointId}", 10L))
                .andExpect(status().isNoContent());

        verify(checkpointRepository).delete(existing);
    }

    @Test
    void delete_returns400WhenCheckpointNotFound() throws Exception {
        when(checkpointRepository.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(delete("/api/checkpoints/{checkpointId}", 999L))
                .andExpect(status().isBadRequest());

        verify(checkpointRepository, never()).delete(any(Checkpoint.class));
    }
}
