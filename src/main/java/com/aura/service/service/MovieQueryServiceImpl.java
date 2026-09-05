package com.aura.service.service;

import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.TimePeriod;
import com.aura.service.proxy.TtlCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class MovieQueryServiceImpl implements MovieQueryService {

    private static final String MOVIE_DETAILS_PLACEHOLDER = "[Movie Details]";
    private static final String USER_QUESTION_PLACEHOLDER = "[User Question]";
    private static final TimePeriod REPORT_PERIOD = TimePeriod.DAY30;
    private static final int REPORT_WINDOW_DAYS = 7;
    private static final int REPORT_CACHE_MAX_ENTRIES = 500;

    private final EntityAccessService entityAccessService;
    private final EntityMarketingReportService entityMarketingReportService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final TtlCache<EntityMarketingReportResponse> reportCache = new TtlCache<>(REPORT_CACHE_MAX_ENTRIES);

    @Value("${llm.prompt.ask.about.movie}")
    private String promptTemplate;

    @Value("${movie-query.report-cache.ttl-seconds:300}")
    private long reportCacheTtlSeconds;

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

        EntityMarketingReportResponse report = getCachedOrGenerateReport(entity);
        JsonNode movieDetails = objectMapper.valueToTree(report);

        String prompt = promptTemplate
                .replace(MOVIE_DETAILS_PLACEHOLDER, movieDetails.toString())
                .replace(USER_QUESTION_PLACEHOLDER, userPrompt);

        return llmService.generateReply(prompt);
    }

    // Repeated questions about the same movie are common in a single session; the report pulls
    // together several downstream analytics calls plus an upstream AuraMath fetch, so it's worth
    // reusing across asks for a short window rather than rebuilding it on every question.
    private EntityMarketingReportResponse getCachedOrGenerateReport(ManagedEntity entity) {
        String cacheKey = String.valueOf(entity.getId());
        EntityMarketingReportResponse cached = reportCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        EntityMarketingReportResponse report = entityMarketingReportService.getReport(
                entity.getType(), entity.getId(), REPORT_PERIOD, REPORT_WINDOW_DAYS, false);
        reportCache.put(cacheKey, report, Duration.ofSeconds(reportCacheTtlSeconds).toNanos());
        return report;
    }
}
