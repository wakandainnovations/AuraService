package com.aura.service.controller;

import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityMarketingReportResponse.HeadlineMetrics;
import com.aura.service.enums.TimePeriod;
import com.aura.service.service.EntitlementServiceImpl;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.EntityMarketingReportPdfService;
import com.aura.service.service.EntityMarketingReportService;
import com.aura.service.service.LicenseService;
import com.aura.service.service.PreviewMaskingServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntityMarketingReportControllerTest {

    private static final Long ENTITY_ID = 42L;

    private StubReportService reportService;
    private StubPdfService pdfService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reportService = new StubReportService();
        pdfService = new StubPdfService();
        EntityAccessService entityAccess = mock(EntityAccessService.class);
        when(entityAccess.currentUserIsAdmin()).thenReturn(true);
        EntityMarketingReportController controller =
                new EntityMarketingReportController(reportService, pdfService,
                        new EntitlementServiceImpl(mock(LicenseService.class), entityAccess,
                                new PreviewMaskingServiceImpl()));

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(mapper),
                        new ByteArrayHttpMessageConverter())
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
                .andExpect(jsonPath("$.data.entity.name").value("Vikram"))
                .andExpect(jsonPath("$.data.period").value("DAY30"))
                .andExpect(jsonPath("$.data.headlineMetrics.totalMentions").value(8000))
                .andExpect(jsonPath("$.data.highlights[0]").value("70% of all mentions are positive"))
                .andExpect(jsonPath("$.data.auraMathStatus").value("ok"));

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

    @Test
    void returnsPdf_withAttachmentHeaders() throws Exception {
        EntityDetailResponse entity = new EntityDetailResponse();
        entity.setName("Vikram");
        reportService.response = EntityMarketingReportResponse.builder().entity(entity).build();
        pdfService.bytes = "%PDF-1.4 stub".getBytes(StandardCharsets.ISO_8859_1);
        pdfService.fileName = "marketing-report-vikram.pdf";

        mvc.perform(get("/api/entities/{entityType}/{id}/marketing-report/pdf", "movie", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"marketing-report-vikram.pdf\""))
                .andExpect(content().bytes(pdfService.bytes));

        // The PDF route flows through the same generation path with the same defaults.
        org.assertj.core.api.Assertions.assertThat(reportService.lastType).isEqualTo("MOVIE");
        org.assertj.core.api.Assertions.assertThat(reportService.lastPeriod).isEqualTo(TimePeriod.DAY30);
        org.assertj.core.api.Assertions.assertThat(pdfService.rendered).isSameAs(reportService.response);
    }

    static class StubPdfService extends EntityMarketingReportPdfService {
        byte[] bytes = new byte[0];
        String fileName = "marketing-report-entity.pdf";
        EntityMarketingReportResponse rendered;

        @Override
        public byte[] render(EntityMarketingReportResponse report) {
            this.rendered = report;
            return bytes;
        }

        @Override
        public String fileName(EntityMarketingReportResponse report) {
            return fileName;
        }
    }

    static class StubReportService extends EntityMarketingReportService {
        EntityMarketingReportResponse response;
        String lastType;
        TimePeriod lastPeriod;
        int lastWindowDays;
        boolean lastRefresh;

        StubReportService() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, java.time.Clock.systemUTC());
        }

        @Override
        public EntityMarketingReportResponse getReport(String entityType, Long id,
                                                       TimePeriod period, int windowDays, boolean refresh) {
            this.lastType = entityType;
            this.lastPeriod = period;
            this.lastWindowDays = windowDays;
            this.lastRefresh = refresh;
            return response;
        }
    }
}
