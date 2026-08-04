package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityDetailResponse {
    private Long id;
    private String name;
    private String type;
    private Long ownerId;
    private String director;
    private List<String> actors = new ArrayList<>();
    private List<KeywordDto> keywords = new ArrayList<>();
    private List<EntityBasicInfo> competitors = new ArrayList<>();
    private LocalDate releaseDate;
    private String language;
    private String industry;
    private List<String> genre = new ArrayList<>();
    private String synopsis;
    private Double budget;
    private String productionCompany;
    private Integer runtime;
    private String releaseDay;
    private Double gdpUsdBillions;
    private Double inflationRatePct;
    private String imageUrl;
    private String imagePath;
}
