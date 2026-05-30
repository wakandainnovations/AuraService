package com.aura.service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReplyTemplateRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String body;

    private String tone;
}
