package com.aura.service.dto;

import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MentionResponse {
    private Long id;
    private Long managedEntityId;
    private Platform platform;
    private String postId;
    private String content;
    private String author;
    private Instant postDate;
    private Sentiment sentiment;
    private String permalink;
    private Short sentimentScore;

    /** Impression/view count of the post as reported by the source platform, or "NA" when unavailable. */
    private String impressions;

    @JsonProperty("available_actions")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> availableActions;

    @JsonProperty("action_history_summary")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ActionHistorySummary actionHistorySummary;

    public MentionResponse(Long id, Long managedEntityId, Platform platform, String postId,
                           String content, String author, Instant postDate, Sentiment sentiment,
                           String permalink, Short sentimentScore, String impressions) {
        this(id, managedEntityId, platform, postId, content, author, postDate, sentiment,
                permalink, sentimentScore, impressions, null, null);
    }
}
