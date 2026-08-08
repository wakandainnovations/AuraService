package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorTypeCount {
    private int rank;
    private String authorType;
    private long count;
    private double sharePct;
}
