package com.aura.service.controller;

import com.aura.service.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Plain (ungated, no EntitledResponse envelope) reference-data endpoints. */
class CheckpointCatalogControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CheckpointCatalogController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void stages_returnsAllNineStagesUngated() throws Exception {
        mvc.perform(get("/api/checkpoints/catalog/stages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$[0].displayName").value("Pre-Announcement"))
                .andExpect(jsonPath("$[0].windowComputedFromRelease").value(false))
                .andExpect(jsonPath("$[5].displayName").value("Theatrical Window"))
                .andExpect(jsonPath("$[5].windowComputedFromRelease").value(true));
    }

    @Test
    void anchorTypes_returnsAllFourOptionsUngated() throws Exception {
        mvc.perform(get("/api/checkpoints/catalog/anchor-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].name").value("Casting / Influencer"));
    }
}
