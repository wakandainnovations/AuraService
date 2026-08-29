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
        REVIEW_ASPECT_OVERRIDE,
        TOPIC_CATEGORY_OVERRIDE,
        AUTHOR_TYPE_OVERRIDE,
        CONTENT_INTENT_OVERRIDE,
        REGION_OVERRIDE
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

    /** Set only for {@code REVIEW_ASPECT_OVERRIDE} — the one taxonomy with a fixed Java enum. */
    private ReviewAspectCategory previousCategory;
    private ReviewAspectCategory newCategory;

    /**
     * Set only for {@code TOPIC_CATEGORY_OVERRIDE}/{@code AUTHOR_TYPE_OVERRIDE}/
     * {@code CONTENT_INTENT_OVERRIDE}/{@code REGION_OVERRIDE} — plain strings, since none of those
     * upstream taxonomies have a fixed enum here.
     */
    private String previousCategoryValue;
    private String newCategoryValue;

    private String reason;
}
