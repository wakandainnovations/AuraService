package com.aura.service.licensing;

import com.aura.service.enums.LicenseTier;
import com.aura.service.exception.InsufficientTierException;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.LicenseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresTier} on the way in. For a handler carrying the annotation (method-level wins
 * over class-level), the request is allowed when the caller is an admin (full bypass) or their
 * {@link LicenseService#effectiveTier() effective} {@link LicenseTier}
 * {@link LicenseTier#isAtLeast(LicenseTier) is at least} the required minimum (so a redeemed offer-key
 * override counts toward reaching a gated feature);
 * otherwise it throws {@link InsufficientTierException}, which {@code GlobalExceptionHandler} renders as
 * the structured {@code 403 { feature, requiredTier }} body.
 *
 * <p>The gate runs only when an annotation is present, so unannotated endpoints are untouched. It never
 * blocks routing — every gated endpoint still dispatches to its handler unless this gate trips.
 */
@Component
@RequiredArgsConstructor
public class TierGateInterceptor implements HandlerInterceptor {

    private final LicenseService licenseService;
    private final EntityAccessService entityAccessService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // Static resources, error dispatches, etc. — nothing to gate.
            return true;
        }

        RequiresTier gate = resolveGate(handlerMethod);
        if (gate == null) {
            return true;
        }

        // Admins reach every premium feature regardless of the tier on their license (if any).
        if (entityAccessService.currentUserIsAdmin()) {
            return true;
        }

        if (!licenseService.effectiveTier().isAtLeast(gate.value())) {
            throw new InsufficientTierException(gate.feature(), gate.value());
        }
        return true;
    }

    /** Method-level annotation takes precedence over a class-level one. */
    private RequiresTier resolveGate(HandlerMethod handlerMethod) {
        RequiresTier methodGate = handlerMethod.getMethodAnnotation(RequiresTier.class);
        if (methodGate != null) {
            return methodGate;
        }
        return handlerMethod.getBeanType().getAnnotation(RequiresTier.class);
    }
}
