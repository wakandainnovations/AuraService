package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplyTemplateResponse {
    private Long id;
    private String name;
    private String body;
    private String tone;
    private int useCount;
    private Instant createdAt;
}
