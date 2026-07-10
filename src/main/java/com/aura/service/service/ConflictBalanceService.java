package com.aura.service.service;

import com.aura.service.dto.ConflictBalanceScore;

public interface ConflictBalanceService {
    ConflictBalanceScore getConflictBalance(Long movieId);
}
