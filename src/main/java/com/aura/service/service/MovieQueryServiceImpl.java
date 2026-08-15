package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MovieQueryServiceImpl implements MovieQueryService {

    private static final String MOVIE_DETAILS_PLACEHOLDER = "[Movie Details]";
    private static final String USER_QUESTION_PLACEHOLDER = "[User Question]";

    private final EntityAccessService entityAccessService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    @Value("${llm.prompt.ask.about.movie}")
    private String promptTemplate;

    public MovieQueryServiceImpl(EntityAccessService entityAccessService, LLMService llmService,
                                  ObjectMapper objectMapper) {
        this.entityAccessService = entityAccessService;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String askAboutMovie(Long entityId, String userPrompt) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(entityId);
        ObjectNode movieDetails = buildMovieDetails(entity);

        String prompt = promptTemplate
                .replace(MOVIE_DETAILS_PLACEHOLDER, movieDetails.toString())
                .replace(USER_QUESTION_PLACEHOLDER, userPrompt);

        return llmService.generateReply(prompt);
    }

    private ObjectNode buildMovieDetails(ManagedEntity entity) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", entity.getId());
        node.put("name", entity.getName());
        putIfPresent(node, "type", entity.getType());
        putIfPresent(node, "director", entity.getDirector());
        List<String> actors = entity.getActors();
        if (actors != null && !actors.isEmpty()) {
            node.put("actors", String.join(", ", actors));
        }
        if (entity.getReleaseDate() != null) {
            node.put("releaseDate", entity.getReleaseDate().toString());
        }
        putIfPresent(node, "releaseDay", entity.getReleaseDay());
        putIfPresent(node, "language", entity.getLanguage());
        putIfPresent(node, "industry", entity.getIndustry());
        putIfPresent(node, "genre", entity.getGenre());
        putIfPresent(node, "synopsis", entity.getSynopsis());
        if (entity.getBudget() != null) {
            node.put("budget", entity.getBudget());
        }
        putIfPresent(node, "productionCompany", entity.getProductionCompany());
        if (entity.getRuntime() != null) {
            node.put("runtimeMinutes", entity.getRuntime());
        }
        return node;
    }

    private void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
