package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeywordDto {
    private String keyword;
    private String category;
    private String language;
    private String state;
    private String industry;
    private String genre;
}
