package com.aura.service.predictor;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Every actor, director, music director and production company named in
 * {@code india_movies_data_collection}, as sorted lists of names. Any authenticated user (falls
 * under {@code anyRequest().authenticated()} in {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/movie-credits")
@RequiredArgsConstructor
public class MovieCreditsController {

    private final MovieCreditsLookup movieCreditsLookup;

    @GetMapping("/actors")
    public ResponseEntity<List<String>> getActors() {
        return ResponseEntity.ok(movieCreditsLookup.actors());
    }

    @GetMapping("/directors")
    public ResponseEntity<List<String>> getDirectors() {
        return ResponseEntity.ok(movieCreditsLookup.directors());
    }

    @GetMapping("/music-directors")
    public ResponseEntity<List<String>> getMusicDirectors() {
        return ResponseEntity.ok(movieCreditsLookup.musicDirectors());
    }

    @GetMapping("/production-companies")
    public ResponseEntity<List<String>> getProductionCompanies() {
        return ResponseEntity.ok(movieCreditsLookup.productionCompanies());
    }
}
