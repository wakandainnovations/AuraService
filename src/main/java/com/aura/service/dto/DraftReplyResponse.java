package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DraftReplyResponse {
    private MentionResponse mention;
    private Long draftId;
    private String generatedText;
}
