package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Partial-update request for a playbook. Any field left {@code null} is left unchanged,
 * letting the UI edit the title, tags, favorite flag, or the AI-drafted text independently.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePlaybookRequest {
    private String title;
    private String planText;
    private List<String> tags;
    private Boolean isFavorite;
}
