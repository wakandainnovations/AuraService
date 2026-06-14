package com.aura.service.licensing;

import com.aura.service.enums.LicenseTier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative premium-feature gate. Place on a controller class (gates every endpoint it serves) or
 * on a single handler method (gates just that endpoint). The request is allowed only when the calling
 * user's {@link LicenseTier} {@link LicenseTier#isAtLeast(LicenseTier) is at least} {@link #value()};
 * otherwise {@link TierGateInterceptor} rejects it with a structured {@code 403}. Holders of
 * {@code ROLE_ADMIN} bypass the gate entirely.
 *
 * <p>The endpoints are deliberately <em>not</em> hidden from routing — they stay callable so the UI can
 * surface them, and the gate answers with a clean {@code { feature, requiredTier }} body that a later
 * feature can turn into a blurred preview.
 *
 * <p>A method-level annotation overrides a class-level one for that handler.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresTier {

    /** The minimum tier a non-admin user must hold to reach the annotated endpoint(s). */
    LicenseTier value();

    /**
     * Human-readable feature name echoed back in the {@code 403} body's {@code feature} field so the UI
     * knows which premium capability was gated (e.g. {@code "Checkpoints"}, {@code "Crisis Management"}).
     */
    String feature();
}
