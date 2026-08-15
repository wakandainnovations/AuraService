package com.aura.service.dto;

import com.aura.service.enums.RecommendedActionStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRecommendedActionStatusRequest {

    @NotNull
    private RecommendedActionStatus status;
}
