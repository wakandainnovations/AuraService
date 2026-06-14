package com.aura.service.controller;

import com.aura.service.dto.GenerateCrisisPlanRequest;
import com.aura.service.dto.GenerateCrisisPlanResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.LicenseTier;
import com.aura.service.licensing.RequiresTier;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.LLMService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/crisis")
@RequiredArgsConstructor
@RequiresTier(value = LicenseTier.GOLD, feature = "Crisis Management")
public class CrisisController {

    private final LLMService llmService;
    private final EntityAccessService entityAccessService;

    @Value("${llm.prompt.generate.crisis.plan}")
    private String crisisPlanPromptTemplate;

    @PostMapping("/generate-plan")
    public ResponseEntity<GenerateCrisisPlanResponse> generateCrisisPlan(
            @Valid @RequestBody GenerateCrisisPlanRequest request
    ) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(request.getEntityId());

        String prompt = crisisPlanPromptTemplate
                .replace("[Managed Entity]", entity.getName())
                .replace("[Crisis Description]", request.getCrisisDescription());

        String generatedPlan = llmService.generateCrisisPlan(prompt);
        return ResponseEntity.ok(new GenerateCrisisPlanResponse(generatedPlan));
    }
}
