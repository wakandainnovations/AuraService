package com.aura.service.controller;

import com.aura.service.dto.LanguageAudienceResponse;
import com.aura.service.dto.MovieAudienceDetailResponse;
import com.aura.service.dto.MovieBudgetComparisonResponse;
import com.aura.service.service.MovieAudienceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audience-size analytics over tracked {@code MOVIE} entities: unique posters per language, per
 * movie (with per-user engagement), and how a movie's audience compares to similarly-budgeted
 * movies. Only mentions with a non-zero sentiment score are counted. Movies are scoped to the
 * caller's own entities, same as {@link EntityController} - see {@code EntityAccessService}.
 */
@RestController
@RequestMapping("/api/movies/audience")
@RequiredArgsConstructor
public class MovieAudienceController {

    private final MovieAudienceService movieAudienceService;

    /** Total unique users who posted about any movie in {@code language}. */
    @GetMapping
    public ResponseEntity<LanguageAudienceResponse> getLanguageAudience(
            @RequestParam String language,
            @RequestParam(required = false) Long ownerId) {
        return ResponseEntity.ok(movieAudienceService.getLanguageAudience(language, ownerId));
    }

    /**
     * Unique users who posted about {@code movieName} in {@code language}, with each user's post
     * count, engagement ratio, average sentiment score, and positive-sentiment ratio.
     */
    @GetMapping("/detail")
    public ResponseEntity<MovieAudienceDetailResponse> getMovieAudienceDetail(
            @RequestParam String language,
            @RequestParam String movieName,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(
                movieAudienceService.getMovieAudienceDetail(language, movieName, ownerId, limit));
    }

    /**
     * Movies budgeted within +-50% of {@code movieName}'s budget, each with its own audience size,
     * to benchmark how {@code movieName} is performing against comparable-budget peers.
     */
    @GetMapping("/budget-comparison")
    public ResponseEntity<MovieBudgetComparisonResponse> getBudgetComparison(
            @RequestParam String movieName,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) Long ownerId) {
        return ResponseEntity.ok(
                movieAudienceService.getBudgetComparison(movieName, language, ownerId));
    }
}
