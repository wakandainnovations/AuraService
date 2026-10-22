package com.aura.service.predictor;

import com.aura.service.predictor.MovieCreditsLookup.RatedName;
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
        public List<RatedName> actors() {
            return List.of(new RatedName("Ajay Devgn", 10_000), new RatedName("Akshay Kumar", null));
        }

        @Override
        public List<RatedName> directors() {
            return List.of(new RatedName("Rajkumar Hirani", 100_000));
        }

        @Override
        public List<RatedName> musicDirectors() {
            return List.of(new RatedName("A. R. Rahman", 1_000));
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
                .andExpect(jsonPath("$[0].name").value("Ajay Devgn"))
                .andExpect(jsonPath("$[0].highestRecentRatingRange").value(10_000))
                .andExpect(jsonPath("$[1].highestRecentRatingRange").doesNotExist());
        mvc.perform(get("/api/movie-credits/directors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Rajkumar Hirani"))
                .andExpect(jsonPath("$[0].highestRecentRatingRange").value(100_000));
        mvc.perform(get("/api/movie-credits/music-directors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("A. R. Rahman"))
                .andExpect(jsonPath("$[0].highestRecentRatingRange").value(1_000));
        mvc.perform(get("/api/movie-credits/production-companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1]").value("T-Series"));
    }
}
