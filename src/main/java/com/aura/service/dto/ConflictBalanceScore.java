package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConflictBalanceScore {
    private int protagonistPower;
    private int antagonistPower;
    private int antagonistMotivationClarity;
    private int stakesEscalation;
    private String rationale;
    private double balanceScore;
}
