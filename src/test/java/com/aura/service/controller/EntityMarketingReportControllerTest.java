package com.aura.service.controller;

import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityMarketingReportResponse.HeadlineMetrics;
import com.aura.service.enums.TimePeriod;
import com.aura.service.service.EntityMarketingReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntityMarketingReportControllerTest {

    private static final Long ENTITY_ID = 42L;

    private StubReportService reportService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reportService = new StubReportService();
        EntityMarketingReportController controller = new EntityMarketingReportController(reportService);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void returnsReport_andDefaultsPeriodToDay30() throws Exception {
        EntityDetailResponse entity = new EntityDetailResponse();
        entity.setId(ENTITY_ID);
        entity.setName("Vikram");
        reportService.response = EntityMarketingReportResponse.builder()
                .generatedAt(Instant.parse("2026-06-11T00:00:00Z"))
                .period("DAY30")
                .entity(entity)
                .headlineMetrics(HeadlineMetrics.builder().totalMentions(8000L).positivityRatio(0.7).build())
                .highlights(List.of("70% of all mentions are positive"))
                .auraMathStatus("ok")
                .build();

        mvc.perform(get("/api/entities/{entityType}/{id}/marketing-report", "movie", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity.name").value("Vikram"))
                .andExpect(jsonPath("$.period").value("DAY30"))
                .andExpect(jsonPath("$.headlineMetrics.totalMentions").value(8000))
                .andExpect(jsonPath("$.highlights[0]").value("70% of all mentions are positive"))
                .andExpect(jsonPath("$.auraMathStatus").value("ok"));

        // entityType is upper-cased before reaching the service, period defaults to DAY30.
        org.assertj.core.api.Assertions.assertThat(reportService.lastType).isEqualTo("MOVIE");
        org.assertj.core.api.Assertions.assertThat(reportService.lastPeriod).isEqualTo(TimePeriod.DAY30);
        org.assertj.core.api.Assertions.assertThat(reportService.lastWindowDays).isEqualTo(7);
    }

    @Test
    void honoursPeriodQueryParam() throws Exception {
        reportService.response = EntityMarketingReportResponse.builder().period("DAY90").build();

        mvc.perform(get("/api/entities/{entityType}/{id}/marketing-report", "movie", ENTITY_ID)
                        .param("period", "DAY90").param("windowDays", "14"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(reportService.lastPeriod).isEqualTo(TimePeriod.DAY90);
        org.assertj.core.api.Assertions.assertThat(reportService.lastWindowDays).isEqualTo(14);
    }

    static class StubReportService extends EntityMarketingReportService {
        EntityMarketingReportResponse response;
        String lastType;
        TimePeriod lastPeriod;
        int lastWindowDays;

        StubReportService() {
            super(null, null, null, null);
        }

        @Override
        public EntityMarketingReportResponse generateReport(String entityType, Long id,
                                                            TimePeriod period, int windowDays) {
            this.lastType = entityType;
            this.lastPeriod = period;
            this.lastWindowDays = windowDays;
            return response;
        }
    }
}
