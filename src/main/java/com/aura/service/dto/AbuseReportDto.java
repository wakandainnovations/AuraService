package com.aura.service.dto;

import com.aura.service.entity.AbuseReport;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * API view of an {@link AbuseReport}. Mirrors every persisted field one-for-one (purely additive
 * over the raw entity) and adds an optional nested {@link MentionSummaryDto} so clients can link a
 * report back to the post it concerns. The nested {@code mention} is {@code null} when the reported
 * mention has since been deleted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AbuseReportDto {

    private Long id;
    private Long mentionId;
    private Long userId;
    private AbuseReport.Category category;
    private String notes;
    private AbuseReport.Status status;
    private String externalRef;
    private Instant submittedAt;
    private Instant resolvedAt;

    /** Summary of the reported mention, or {@code null} if that mention no longer exists. */
    private MentionSummaryDto mention;

    public static AbuseReportDto of(AbuseReport report, MentionSummaryDto mention) {
        return AbuseReportDto.builder()
                .id(report.getId())
                .mentionId(report.getMentionId())
                .userId(report.getUserId())
                .category(report.getCategory())
                .notes(report.getNotes())
                .status(report.getStatus())
                .externalRef(report.getExternalRef())
                .submittedAt(report.getSubmittedAt())
                .resolvedAt(report.getResolvedAt())
                .mention(mention)
                .build();
    }
}
