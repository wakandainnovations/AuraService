package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostReplyResponse {
    private MentionResponse mention;
    private Long draftId;
    private String text;
    private Instant postedAt;
    private String result;
}
