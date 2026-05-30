package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaybookResponse {
    private Long id;
    private Long entityId;
    private Long mentionId;
    private String title;
    private String planText;
    private List<String> tags;
    private boolean isFavorite;
    private Long createdBy;
    private Instant createdAt;
}
