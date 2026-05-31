package com.aura.service.dto;

import com.aura.service.entity.AbuseReport;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportAbuseRequest {

    @NotNull(message = "category is required")
    private AbuseReport.Category category;

    private String notes;
}
