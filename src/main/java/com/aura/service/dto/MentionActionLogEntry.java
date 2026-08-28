package com.aura.service.dto;

import com.aura.service.entity.ReplyDraft;
import com.aura.service.enums.ReviewAspectCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MentionActionLogEntry {

    public enum Type {
        REPLY_DRAFT,
        CRISIS_PLAN,
        MOBILIZE,
        REVIEW_ASPECT_OVERRIDE
    }

    private Type type;
    private Long id;
    private String actor;
    private Instant createdAt;

    private ReplyDraft.Status draftStatus;
    private String text;
    private Instant postedAt;

    private String planText;

    private Integer allyCount;

    private ReviewAspectCategory previousCategory;
    private ReviewAspectCategory newCategory;
    private String reason;
}
