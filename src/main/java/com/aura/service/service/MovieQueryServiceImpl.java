package com.aura.service.service;

import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.TimePeriod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MovieQueryServiceImpl implements MovieQueryService {

    private static final String MOVIE_DETAILS_PLACEHOLDER = "[Movie Details]";
    private static final String USER_QUESTION_PLACEHOLDER = "[User Question]";
    private static final TimePeriod REPORT_PERIOD = TimePeriod.DAY30;
    private static final int REPORT_WINDOW_DAYS = 7;

    private final EntityAccessService entityAccessService;
    private final EntityMarketingReportService entityMarketingReportService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    @Value("${llm.prompt.ask.about.movie}")
    private String promptTemplate;

    public MovieQueryServiceImpl(EntityAccessService entityAccessService,
                                  EntityMarketingReportService entityMarketingReportService,
                                  LLMService llmService,
                                  ObjectMapper objectMapper) {
        this.entityAccessService = entityAccessService;
        this.entityMarketingReportService = entityMarketingReportService;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String askAboutMovie(Long entityId, String userPrompt) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(entityId);

        EntityMarketingReportResponse report = entityMarketingReportService.generateReport(
                entity.getType(), entityId, REPORT_PERIOD, REPORT_WINDOW_DAYS);
        JsonNode movieDetails = objectMapper.valueToTree(report);

        String prompt = promptTemplate
                .replace(MOVIE_DETAILS_PLACEHOLDER, movieDetails.toString())
                .replace(USER_QUESTION_PLACEHOLDER, userPrompt);

        return llmService.generateReply(prompt);
    }
}
