package com.aura.service.controller;

import com.aura.service.repository.UserEntityViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DevControllerTest {

    private UserEntityViewRepository viewRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        viewRepository = mock(UserEntityViewRepository.class);
        DevController controller = new DevController(viewRepository);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void resetDemo_resetsAllLastSeenTo30DaysAgo() throws Exception {
        when(viewRepository.resetAllLastSeen(any(Instant.class))).thenReturn(4);
        Instant before = Instant.now().minus(30, ChronoUnit.DAYS);

        mvc.perform(post("/api/dev/reset-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true))
                .andExpect(jsonPath("$.rows_updated").value(4))
                .andExpect(jsonPath("$.last_seen_at").isString());

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(viewRepository).resetAllLastSeen(captor.capture());
        Instant actual = captor.getValue();
        assertThat(actual).isBetween(before, Instant.now().minus(29, ChronoUnit.DAYS));
    }

    @Test
    void resetDemo_returnsZeroWhenNoViewsExist() throws Exception {
        when(viewRepository.resetAllLastSeen(any(Instant.class))).thenReturn(0);

        mvc.perform(post("/api/dev/reset-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true))
                .andExpect(jsonPath("$.rows_updated").value(0));
    }
}
