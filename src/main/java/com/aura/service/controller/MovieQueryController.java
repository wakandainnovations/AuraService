package com.aura.service.controller;

import com.aura.service.dto.AskAboutMovieRequest;
import com.aura.service.dto.AskAboutMovieResponse;
import com.aura.service.service.MovieQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/movie-query")
@RequiredArgsConstructor
public class MovieQueryController {

    private final MovieQueryService movieQueryService;

    @PostMapping("/ask")
    public AskAboutMovieResponse ask(@Valid @RequestBody AskAboutMovieRequest request) {
        String answer = movieQueryService.askAboutMovie(request.getEntityId(), request.getPrompt());
        return new AskAboutMovieResponse(request.getEntityId(), answer);
    }
}
