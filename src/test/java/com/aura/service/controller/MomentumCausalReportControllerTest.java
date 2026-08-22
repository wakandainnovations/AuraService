package com.aura.service.controller;

import com.aura.service.dto.MomentumCausalReportResponse;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.service.MomentumCausalReportPdfService;
import com.aura.service.service.MomentumCausalReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link MomentumCausalReportController} with hand-written stub subclasses of the concrete
 * {@link MomentumCausalReportService}/{@link MomentumCausalReportPdfService} (same style as
 * {@code EntityMarketingReportControllerTest}) - ownership/section-assembly behavior is exercised at
 * the service layer in {@code MomentumCausalReportServiceTest}; this test only verifies the
 * controller wires ownership 404s through and serves both the JSON and PDF endpoints correctly.
 */
class MomentumCausalReportControllerTest {

    private static final Long ENTITY_ID = 42L;

    private StubReportService reportService;
    private StubPdfService pdfService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reportService = new StubReportService();
        pdfService = new StubPdfService();
        MomentumCausalReportController controller = new MomentumCausalReportController(reportService, pdfService);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(mapper),
                        new ByteArrayHttpMessageConverter())
                .build();
    }

    @Test
    void getMomentumReport_notOwned_returns404() throws Exception {
        reportService.toThrow = new ResourceNotFoundException("not found");

        mvc.perform(get("/api/entities/{id}/momentum-report", ENTITY_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMomentumReport_owned_returnsReportJson() throws Exception {
        reportService.response = MomentumCausalReportResponse.builder()
                .entityId(ENTITY_ID)
                .entityName("Test Movie")
                .generatedAt(Instant.parse("2026-06-11T00:00:00Z"))
                .build();

        mvc.perform(get("/api/entities/{id}/momentum-report", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.entityName").value("Test Movie"));

        assertThat(reportService.lastId).isEqualTo(ENTITY_ID);
    }

    @Test
    void getMomentumReportPdf_notOwned_returns404() throws Exception {
        reportService.toThrow = new ResourceNotFoundException("not found");

        mvc.perform(get("/api/entities/{id}/momentum-report/pdf", ENTITY_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMomentumReportPdf_owned_streamsPdfWithFilename() throws Exception {
        reportService.response = MomentumCausalReportResponse.builder()
                .entityId(ENTITY_ID)
                .entityName("Test Movie")
                .generatedAt(Instant.now())
                .build();
        pdfService.bytes = new byte[]{1, 2, 3};
        pdfService.fileName = "momentum-causal-report-test-movie.pdf";

        mvc.perform(get("/api/entities/{id}/momentum-report/pdf", ENTITY_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("momentum-causal-report-test-movie.pdf")));

        assertThat(pdfService.rendered).isSameAs(reportService.response);
    }

    // ------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------

    static class StubReportService extends MomentumCausalReportService {
        MomentumCausalReportResponse response;
        RuntimeException toThrow;
        Long lastId;

        StubReportService() {
            super(null, null, null, null, null);
        }

        @Override
        public MomentumCausalReportResponse buildReport(Long entityId) {
            this.lastId = entityId;
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    static class StubPdfService extends MomentumCausalReportPdfService {
        byte[] bytes = new byte[0];
        String fileName = "momentum-causal-report-entity.pdf";
        MomentumCausalReportResponse rendered;

        @Override
        public byte[] render(MomentumCausalReportResponse report) {
            this.rendered = report;
            return bytes;
        }

        @Override
        public String fileName(MomentumCausalReportResponse report) {
            return fileName;
        }
    }
}
