package com.aura.service.controller;

import com.aura.service.dto.ComparableMovieStats;
import com.aura.service.dto.LanguageAudienceResponse;
import com.aura.service.dto.MovieAudienceDetailResponse;
import com.aura.service.dto.MovieBudgetComparisonResponse;
import com.aura.service.dto.UserEngagementStats;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.service.MovieAudienceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MovieAudienceControllerTest {

    private MovieAudienceService movieAudienceService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        movieAudienceService = mock(MovieAudienceService.class);
        MovieAudienceController controller = new MovieAudienceController(movieAudienceService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ------------------------------------------------------------------
    // GET /api/movies/audience
    // ------------------------------------------------------------------

    @Test
    void getLanguageAudience_returnsServiceResultAsJson() throws Exception {
        LanguageAudienceResponse response =
                new LanguageAudienceResponse("Kannada", 2, 42L, List.of("KGF", "Kantara"));
        when(movieAudienceService.getLanguageAudience("Kannada", null)).thenReturn(response);

        mvc.perform(get("/api/movies/audience").param("language", "Kannada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("Kannada"))
                .andExpect(jsonPath("$.movieCount").value(2))
                .andExpect(jsonPath("$.uniqueAudienceCount").value(42))
                .andExpect(jsonPath("$.movieNames.length()").value(2))
                .andExpect(jsonPath("$.movieNames[0]").value("KGF"));
    }

    @Test
    void getLanguageAudience_passesOwnerIdThroughToService() throws Exception {
        when(movieAudienceService.getLanguageAudience("Tamil", 9L))
                .thenReturn(new LanguageAudienceResponse("Tamil", 0, 0L, List.of()));

        mvc.perform(get("/api/movies/audience").param("language", "Tamil").param("ownerId", "9"))
                .andExpect(status().isOk());

        verify(movieAudienceService).getLanguageAudience("Tamil", 9L);
    }

    @Test
    void getLanguageAudience_returns404WhenServiceThrowsResourceNotFound() throws Exception {
        when(movieAudienceService.getLanguageAudience("Bhojpuri", null))
                .thenThrow(new ResourceNotFoundException("No movies found for language: Bhojpuri"));

        mvc.perform(get("/api/movies/audience").param("language", "Bhojpuri"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // GET /api/movies/audience/detail
    // ------------------------------------------------------------------

    @Test
    void getMovieAudienceDetail_returnsUsersWithEngagementMetadata() throws Exception {
        MovieAudienceDetailResponse response = new MovieAudienceDetailResponse(
                "KGF", "Kannada", 2L, 10L,
                List.of(
                        new UserEngagementStats("alice", 6L, 0.6, 4.5, 0.8333),
                        new UserEngagementStats("bob", 4L, 0.4, 2.0, 0.25)
                ));
        when(movieAudienceService.getMovieAudienceDetail("Kannada", "KGF", null, null)).thenReturn(response);

        mvc.perform(get("/api/movies/audience/detail")
                        .param("language", "Kannada")
                        .param("movieName", "KGF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieName").value("KGF"))
                .andExpect(jsonPath("$.uniqueAudienceCount").value(2))
                .andExpect(jsonPath("$.totalPosts").value(10))
                .andExpect(jsonPath("$.users.length()").value(2))
                .andExpect(jsonPath("$.users[0].author").value("alice"))
                .andExpect(jsonPath("$.users[0].postCount").value(6))
                .andExpect(jsonPath("$.users[0].engagementRatio").value(0.6))
                .andExpect(jsonPath("$.users[1].author").value("bob"));
    }

    @Test
    void getMovieAudienceDetail_passesLimitThroughToService() throws Exception {
        when(movieAudienceService.getMovieAudienceDetail("Kannada", "KGF", null, 5))
                .thenReturn(new MovieAudienceDetailResponse("KGF", "Kannada", 0L, 0L, List.of()));

        mvc.perform(get("/api/movies/audience/detail")
                        .param("language", "Kannada")
                        .param("movieName", "KGF")
                        .param("limit", "5"))
                .andExpect(status().isOk());

        verify(movieAudienceService).getMovieAudienceDetail("Kannada", "KGF", null, 5);
    }

    @Test
    void getMovieAudienceDetail_returns404WhenServiceThrowsResourceNotFound() throws Exception {
        when(movieAudienceService.getMovieAudienceDetail(eq("Kannada"), eq("Unknown"), isNull(), any()))
                .thenThrow(new ResourceNotFoundException("No movie found"));

        mvc.perform(get("/api/movies/audience/detail")
                        .param("language", "Kannada")
                        .param("movieName", "Unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMovieAudienceDetail_neverCallsServiceWhenRequiredMovieNameParamMissing() throws Exception {
        // Spring rejects the request before the controller method runs (missing @RequestParam);
        // this only asserts the service is never invoked without a movieName.
        mvc.perform(get("/api/movies/audience/detail").param("language", "Kannada"));

        verify(movieAudienceService, org.mockito.Mockito.never())
                .getMovieAudienceDetail(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // GET /api/movies/audience/budget-comparison
    // ------------------------------------------------------------------

    @Test
    void getBudgetComparison_returnsTargetAndComparablePeers() throws Exception {
        MovieBudgetComparisonResponse response = new MovieBudgetComparisonResponse(
                "KGF", "Kannada", 1_000_000.0, 40L, 100L, 50.0,
                750_000.0, 1_250_000.0,
                List.of(new ComparableMovieStats("RRR", "Telugu", 900_000.0, 80L, 200L, 2.0)));
        when(movieAudienceService.getBudgetComparison("KGF", "Kannada", null)).thenReturn(response);

        mvc.perform(get("/api/movies/audience/budget-comparison")
                        .param("movieName", "KGF")
                        .param("language", "Kannada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetMovieName").value("KGF"))
                .andExpect(jsonPath("$.targetBudget").value(1_000_000.0))
                .andExpect(jsonPath("$.targetAudiencePercentileInRange").value(50.0))
                .andExpect(jsonPath("$.budgetRangeMinUsd").value(750_000.0))
                .andExpect(jsonPath("$.budgetRangeMaxUsd").value(1_250_000.0))
                .andExpect(jsonPath("$.comparableMovies.length()").value(1))
                .andExpect(jsonPath("$.comparableMovies[0].movieName").value("RRR"))
                .andExpect(jsonPath("$.comparableMovies[0].audiencePercentileInRange").value(2.0));
    }

    @Test
    void getBudgetComparison_languageParamIsOptional() throws Exception {
        when(movieAudienceService.getBudgetComparison("KGF", null, null))
                .thenReturn(new MovieBudgetComparisonResponse(
                        "KGF", "Kannada", 1_000_000.0, 0L, 0L, null, 750_000.0, 1_250_000.0, List.of()));

        mvc.perform(get("/api/movies/audience/budget-comparison").param("movieName", "KGF"))
                .andExpect(status().isOk());

        verify(movieAudienceService).getBudgetComparison("KGF", null, null);
    }

    @Test
    void getBudgetComparison_returns400WithDisambiguationMessageWhenMovieNameCollidesAcrossLanguages() throws Exception {
        // e.g. "Vikram" exists as both a Kannada and a Tamil movie; without a language param the
        // service can't pick one and asks the caller to disambiguate.
        when(movieAudienceService.getBudgetComparison("Vikram", null, null))
                .thenThrow(new IllegalArgumentException(
                        "Multiple movies named 'Vikram' found; pass language to disambiguate"));

        mvc.perform(get("/api/movies/audience/budget-comparison").param("movieName", "Vikram"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Multiple movies named 'Vikram' found; pass language to disambiguate"));

        verify(movieAudienceService).getBudgetComparison("Vikram", null, null);
    }

    @Test
    void getBudgetComparison_returns404WhenServiceThrowsResourceNotFound() throws Exception {
        when(movieAudienceService.getBudgetComparison("Unknown", null, null))
                .thenThrow(new ResourceNotFoundException("No movie found named: Unknown"));

        mvc.perform(get("/api/movies/audience/budget-comparison").param("movieName", "Unknown"))
                .andExpect(status().isNotFound());
    }
}
