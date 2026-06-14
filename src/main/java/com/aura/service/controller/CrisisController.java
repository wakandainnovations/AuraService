package com.aura.service.controller;

import com.aura.service.dto.EntitledResponse;
import com.aura.service.dto.GenerateCrisisPlanRequest;
import com.aura.service.dto.GenerateCrisisPlanResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.licensing.Feature;
import com.aura.service.service.EntitlementService;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.LLMService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * Crisis Management — a {@link Feature#CRISIS GOLD}-tier feature. Under-tier users are no longer
 * rejected with a {@code 403}; they get a {@code 200} with a masked, blurred preview of the generated
 * plan, while entitled users get the real plan.
 */
@RestController
@RequestMapping("/api/crisis")
@RequiredArgsConstructor
public class CrisisController {

    private final LLMService llmService;
    private final EntityAccessService entityAccessService;
    private final EntitlementService entitlementService;

    @Value("${llm.prompt.generate.crisis.plan}")
    private String crisisPlanPromptTemplate;

    @PostMapping("/generate-plan")
    public EntitledResponse<GenerateCrisisPlanResponse> generateCrisisPlan(
            @Valid @RequestBody GenerateCrisisPlanRequest request
    ) {
        return entitlementService.evaluate(Feature.CRISIS, () -> {
            ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(request.getEntityId());

            String prompt = crisisPlanPromptTemplate
                    .replace("[Managed Entity]", entity.getName())
                    .replace("[Crisis Description]", request.getCrisisDescription());

            String generatedPlan = llmService.generateCrisisPlan(prompt);
            return new GenerateCrisisPlanResponse(generatedPlan);
        });
    }
}
