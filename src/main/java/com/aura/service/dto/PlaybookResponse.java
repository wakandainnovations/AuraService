package com.aura.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    // Without this, Lombok's boolean getter isFavorite() serializes as "favorite",
    // mismatching the "isFavorite" key the update request accepts.
    @JsonProperty("isFavorite")
    private boolean isFavorite;
    private Long createdBy;
    private Instant createdAt;
}
