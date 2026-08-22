package com.aura.service.service;

import com.aura.service.dto.MomentumCausalReportResponse;
import com.aura.service.dto.MomentumCausalReportResponse.CausalLiftUser;
import com.aura.service.dto.MomentumCausalReportResponse.StatisticalCandidateSection;
import com.aura.service.dto.MomentumCausalReportResponse.TopCausalLiftUsersSection;
import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.enums.RecommendedActionCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@link MomentumCausalReportPdfService}: both a fully-populated report and one
 * whose sections are all "insufficient_history" placeholders must render without throwing - the PDF
 * endpoint must never 500 on a freshly-tracked entity's report either.
 */
class MomentumCausalReportPdfServiceTest {

    private final MomentumCausalReportPdfService pdfService = new MomentumCausalReportPdfService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void render_fullyPopulatedReport_producesNonEmptyPdf() throws Exception {
        var vmi = mapper.readTree("{\"status\":\"ok\",\"series\":[{\"day_index\":1,"
                + "\"daily_engagement_volume\":100,\"cohort_zscore\":1.2,\"cumulative_engagement_volume\":100}],"
                + "\"peakDay\":{\"dayIndex\":1,\"calendarDate\":\"2026-01-01\",\"dailyEngagementVolume\":100}}");
        var causalChains = mapper.readTree("{\"status\":\"ok\",\"chains\":[{\"pathScore\":0.9,\"edges\":["
                + "{\"from_series\":\"vmi\",\"to_series\":\"sentiment\",\"lag\":2,\"fdr_q_value\":0.01,"
                + "\"effect_size_r2\":0.55,\"n_entities_supporting\":12}]}]}");

        RecommendedActionCandidate lever = new RecommendedActionCandidate(
                "nonobvious-lever-foo", "Foo Lever", RecommendedActionCategory.MEDIUM_IMPACT, 85,
                -120, -1, "some window", List.of(), List.of(), List.of(),
                new RecommendedActionCandidate.StatisticalEvidence("foo", "positive", 0.001, 0.02, 50L,
                        null, null, null));

        MomentumCausalReportResponse report = MomentumCausalReportResponse.builder()
                .entityId(42L)
                .entityName("Test Movie")
                .generatedAt(Instant.now())
                .vmiTrend(vmi)
                .causalChains(causalChains)
                .topCausalLiftUsers(TopCausalLiftUsersSection.builder()
                        .status("ok")
                        .users(List.of(CausalLiftUser.builder()
                                .globalUserId("u1").causalLiftScore(1.5).confidence("HIGH")
                                .nQualifyingEvents(3L).mentionCount(10L).build()))
                        .build())
                .nonObviousLevers(StatisticalCandidateSection.builder()
                        .status("ok").candidates(List.of(lever)).build())
                .playbookMatches(StatisticalCandidateSection.builder()
                        .status("insufficient_history").details("no cohort history yet")
                        .build())
                .build();

        byte[] pdf = pdfService.render(report);

        assertThat(pdf).isNotEmpty();
        assertThat(pdfService.fileName(report)).isEqualTo("momentum-causal-report-test-movie.pdf");
    }

    @Test
    void render_allSectionsInsufficientHistory_stillProducesPdf() {
        var insufficient = mapper.createObjectNode()
                .put("status", "insufficient_history")
                .put("details", "no rows yet");

        MomentumCausalReportResponse report = MomentumCausalReportResponse.builder()
                .entityId(7L)
                .entityName(null)
                .generatedAt(Instant.now())
                .vmiTrend(insufficient)
                .causalChains(insufficient)
                .topCausalLiftUsers(TopCausalLiftUsersSection.builder()
                        .status("insufficient_history").details("no scored users yet").build())
                .nonObviousLevers(StatisticalCandidateSection.builder()
                        .status("insufficient_history").details("no lever findings yet").build())
                .playbookMatches(StatisticalCandidateSection.builder()
                        .status("insufficient_history").details("no playbook findings yet").build())
                .build();

        byte[] pdf = pdfService.render(report);

        assertThat(pdf).isNotEmpty();
        assertThat(pdfService.fileName(report)).isEqualTo("momentum-causal-report-entity.pdf");
    }
}
