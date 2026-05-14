package com.aura.service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityKeyword {

    @Column(name = "keyword")
    private String keyword;

    @Column(name = "category")
    private String category;

    @Column(name = "language", columnDefinition = "TEXT")
    private String language;

    @Column(name = "state", columnDefinition = "TEXT")
    private String state;

    @Column(name = "industry", columnDefinition = "TEXT")
    private String industry;
}
