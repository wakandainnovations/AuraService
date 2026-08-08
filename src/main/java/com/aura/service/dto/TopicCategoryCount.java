package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopicCategoryCount {
    private int rank;
    private String topicCategory;
    private long count;
    private double sharePct;
}
