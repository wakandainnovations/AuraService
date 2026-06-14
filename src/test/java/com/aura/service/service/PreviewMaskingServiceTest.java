package com.aura.service.service;

import com.aura.service.dto.EntityMarketingReportResponse;
import com.aura.service.dto.EntityMarketingReportResponse.HeadlineMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The masking contract: the preview keeps the payload's <em>shape</em> but leaks no real value —
 * strings become a fixed placeholder, numbers become digit-free buckets (never the exact value), lists
 * are truncated, booleans are dropped.
 */
class PreviewMaskingServiceTest {

    private final PreviewMaskingService masking = new PreviewMaskingServiceImpl();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void mask_null_returnsNull() {
        assertThat(masking.mask(null)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_keepsShape_butStarsStrings_dropsBooleans_andBucketsNumbers() {
        Map<String, Object> payload = Map.of(
                "name", "TopSecretInfluencer",
                "mentions", 91234,
                "ratio", 0.873,
                "leads", true);

        Map<String, Object> masked = (Map<String, Object>) masking.mask(payload);

        // Shape preserved: every key survives.
        assertThat(masked).containsKeys("name", "mentions", "ratio", "leads");
        // Strings → fixed star token; no original character survives.
        assertThat(masked.get("name")).isEqualTo(PreviewMaskingServiceImpl.MASKED_TEXT);
        // Numbers → coarse, digit-free magnitude buckets, never the exact value.
        assertThat(masked.get("mentions")).isEqualTo("many thousands");
        assertThat(masked.get("ratio")).isEqualTo("a handful");
        // Booleans are a real value too — dropped.
        assertThat(masked.get("leads")).isNull();
    }

    @Test
    void mask_truncatesListsToTeaserLength() throws Exception {
        Map<String, Object> payload = Map.of(
                "tags", List.of("alpha", "bravo", "charlie", "delta"));

        String json = mapper.writeValueAsString(masking.mask(payload));

        assertThat(mapper.readTree(json).get("tags").size())
                .isEqualTo(PreviewMaskingServiceImpl.TEASER_LENGTH);
        assertThat(json)
                .doesNotContain("alpha")
                .doesNotContain("bravo")
                .doesNotContain("charlie")
                .doesNotContain("delta");
    }

    @Test
    void mask_realReportPayload_leaksNoUnderlyingValue() throws Exception {
        EntityMarketingReportResponse report = EntityMarketingReportResponse.builder()
                .generatedAt(Instant.parse("2026-06-11T00:00:00Z"))
                .period("DAY30")
                .headlineMetrics(HeadlineMetrics.builder()
                        .totalMentions(91234L)
                        .positivityRatio(0.873)
                        .platformsCovered(7)
                        .build())
                .highlights(List.of("91% of all mentions are positive", "Top spreader: @superfan"))
                .auraMathStatus("ok")
                .build();

        String json = mapper.writeValueAsString(masking.mask(report));

        // None of the real values may survive into the preview.
        assertThat(json)
                .doesNotContain("91234")
                .doesNotContain("0.873")
                .doesNotContain("DAY30")
                .doesNotContain("ok")
                .doesNotContain("superfan")
                .doesNotContain("91% of all mentions are positive")
                .doesNotContain("2026-06-11");
        // But the shape is still there for the UI to render a believable blur.
        assertThat(json).contains("headlineMetrics").contains("totalMentions").contains("highlights");
    }

    @Test
    void bucket_mapsMagnitudesToDigitFreeLabels() {
        assertThat(PreviewMaskingServiceImpl.bucket(0)).isEqualTo("none");
        assertThat(PreviewMaskingServiceImpl.bucket(7)).isEqualTo("a handful");
        assertThat(PreviewMaskingServiceImpl.bucket(42)).isEqualTo("dozens");
        assertThat(PreviewMaskingServiceImpl.bucket(530)).isEqualTo("hundreds");
        assertThat(PreviewMaskingServiceImpl.bucket(8_000)).isEqualTo("thousands");
        assertThat(PreviewMaskingServiceImpl.bucket(91_234)).isEqualTo("many thousands");
        assertThat(PreviewMaskingServiceImpl.bucket(5_000_000)).isEqualTo("millions+");
        // No bucket label contains a digit, so an exact value can never leak through it.
        for (double v : new double[]{0, 7, 42, 530, 8_000, 91_234, 5_000_000}) {
            assertThat(PreviewMaskingServiceImpl.bucket(v)).doesNotMatch(".*\\d.*");
        }
    }
}
