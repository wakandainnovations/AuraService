package com.aura.service.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.ArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateKeywordsRequest {
    private List<KeywordDto> keywords = new ArrayList<>();
}
