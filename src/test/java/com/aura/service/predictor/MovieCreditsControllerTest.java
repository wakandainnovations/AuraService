package com.aura.service.predictor;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MovieCreditsControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MovieCreditsController(new MovieCreditsLookup() {
        @Override
        public List<String> actors() {
            return List.of("Ajay Devgn", "Akshay Kumar");
        }

        @Override
        public List<String> directors() {
            return List.of("Rajkumar Hirani");
        }

        @Override
        public List<String> musicDirectors() {
            return List.of("A. R. Rahman");
        }

        @Override
        public List<String> productionCompanies() {
            return List.of("Geetha Arts", "T-Series");
        }
    })).build();

    @Test
    void returnsEachRolesNames() throws Exception {
        mvc.perform(get("/api/movie-credits/actors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("Ajay Devgn"));
        mvc.perform(get("/api/movie-credits/directors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Rajkumar Hirani"));
        mvc.perform(get("/api/movie-credits/music-directors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("A. R. Rahman"));
        mvc.perform(get("/api/movie-credits/production-companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1]").value("T-Series"));
    }
}
