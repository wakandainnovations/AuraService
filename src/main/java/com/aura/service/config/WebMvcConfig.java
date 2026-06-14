package com.aura.service.config;

import com.aura.service.licensing.TierGateInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the premium-feature gate into the MVC dispatch chain. The {@link TierGateInterceptor} runs for
 * every request but only acts on handlers annotated with {@code @RequiresTier}, so registering it across
 * {@code /api/**} is enough — unannotated endpoints pass straight through.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TierGateInterceptor tierGateInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tierGateInterceptor).addPathPatterns("/api/**");
    }
}
