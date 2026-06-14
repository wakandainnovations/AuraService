package com.aura.service.licensing;

import com.aura.service.controller.AudienceContentController;
import com.aura.service.controller.CheckpointController;
import com.aura.service.controller.CrisisController;
import com.aura.service.controller.EntityMarketingReportController;
import com.aura.service.controller.MarketingAggregationController;
import com.aura.service.enums.LicenseTier;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.exception.InsufficientTierException;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.LicenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the premium-feature gate per the spec matrix:
 * <ul>
 *   <li>Silver can use Checkpoints (SILVER) but not Crisis (GOLD);</li>
 *   <li>Gold can use Crisis (GOLD) but not Aggregated Intel (DIAMOND);</li>
 *   <li>only Diamond reaches the DIAMOND features (Aggregated Intel, Intelligence Report,
 *       Audience &amp; Content);</li>
 *   <li>an admin reaches everything, regardless of tier.</li>
 * </ul>
 *
 * <p>Only interfaces are mocked ({@link LicenseService}, {@link EntityAccessService}); the real
 * annotated controllers supply genuine {@link HandlerMethod}s so the gate reads the actual
 * {@code @RequiresTier} declarations.
 */
class TierGateInterceptorTest {

    // The real controllers (collaborators left null — the gate runs before any handler body executes).
    private final CheckpointController checkpoints = new CheckpointController(null);
    private final CrisisController crisis = new CrisisController(null, null);
    private final MarketingAggregationController aggregate = new MarketingAggregationController(null, null);
    private final EntityMarketingReportController report = new EntityMarketingReportController(null, null);
    private final AudienceContentController audience = new AudienceContentController();

    private LicenseService licenseService;
    private EntityAccessService entityAccess;
    private TierGateInterceptor interceptor;

    @BeforeEach
    void setUp() {
        licenseService = mock(LicenseService.class);
        entityAccess = mock(EntityAccessService.class);
        interceptor = new TierGateInterceptor(licenseService, entityAccess);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private HandlerMethod handler(Object controller, String methodName) {
        Method method = Arrays.stream(controller.getClass().getMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No method " + methodName));
        return new HandlerMethod(controller, method);
    }

    /** Runs the gate for {@code handler} as a non-admin whose <em>effective</em> tier is {@code tier}. */
    private boolean preHandleAs(LicenseTier tier, HandlerMethod handler) {
        when(entityAccess.currentUserIsAdmin()).thenReturn(false);
        when(licenseService.effectiveTier()).thenReturn(tier);
        return interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler);
    }

    private void assertBlocked(LicenseTier tier, HandlerMethod handler, String feature, LicenseTier required) {
        assertThatThrownBy(() -> preHandleAs(tier, handler))
                .isInstanceOf(InsufficientTierException.class)
                .satisfies(ex -> {
                    InsufficientTierException ite = (InsufficientTierException) ex;
                    assertThat(ite.getFeature()).isEqualTo(feature);
                    assertThat(ite.getRequiredTier()).isEqualTo(required);
                });
    }

    // ------------------------------------------------------------------
    // Checkpoints — SILVER
    // ------------------------------------------------------------------

    @Test
    void silver_canUseCheckpoints() {
        assertThat(preHandleAs(LicenseTier.SILVER, handler(checkpoints, "listByEntity"))).isTrue();
    }

    @Test
    void bronze_cannotUseCheckpoints() {
        assertBlocked(LicenseTier.BRONZE, handler(checkpoints, "listByEntity"), "Checkpoints", LicenseTier.SILVER);
    }

    // ------------------------------------------------------------------
    // Crisis Management — GOLD
    // ------------------------------------------------------------------

    @Test
    void silver_cannotUseCrisis() {
        assertBlocked(LicenseTier.SILVER, handler(crisis, "generateCrisisPlan"), "Crisis Management", LicenseTier.GOLD);
    }

    @Test
    void gold_canUseCrisis() {
        assertThat(preHandleAs(LicenseTier.GOLD, handler(crisis, "generateCrisisPlan"))).isTrue();
    }

    // ------------------------------------------------------------------
    // DIAMOND features — Aggregated Intel, Intelligence Report, Audience & Content
    // ------------------------------------------------------------------

    @Test
    void gold_cannotUseAggregatedIntel() {
        assertBlocked(LicenseTier.GOLD, handler(aggregate, "topSpreaders"), "Aggregated Intel", LicenseTier.DIAMOND);
    }

    @Test
    void onlyDiamondReachesAggregatedIntel() {
        HandlerMethod h = handler(aggregate, "topSpreaders");
        assertBlocked(LicenseTier.GOLD, h, "Aggregated Intel", LicenseTier.DIAMOND);
        assertThat(preHandleAs(LicenseTier.DIAMOND, h)).isTrue();
    }

    @Test
    void onlyDiamondReachesIntelligenceReport() {
        HandlerMethod h = handler(report, "getMarketingReport");
        assertBlocked(LicenseTier.GOLD, h, "Intelligence Report", LicenseTier.DIAMOND);
        assertThat(preHandleAs(LicenseTier.DIAMOND, h)).isTrue();
    }

    @Test
    void onlyDiamondReachesAudienceContent() {
        HandlerMethod h = handler(audience, "placeholder");
        assertBlocked(LicenseTier.GOLD, h, "Audience & Content", LicenseTier.DIAMOND);
        assertThat(preHandleAs(LicenseTier.DIAMOND, h)).isTrue();
    }

    // ------------------------------------------------------------------
    // Admin bypass
    // ------------------------------------------------------------------

    @Test
    void admin_reachesEveryFeatureWithoutConsultingTier() {
        when(entityAccess.currentUserIsAdmin()).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req, res, handler(checkpoints, "listByEntity"))).isTrue();
        assertThat(interceptor.preHandle(req, res, handler(crisis, "generateCrisisPlan"))).isTrue();
        assertThat(interceptor.preHandle(req, res, handler(aggregate, "topSpreaders"))).isTrue();
        assertThat(interceptor.preHandle(req, res, handler(report, "getMarketingReport"))).isTrue();
        assertThat(interceptor.preHandle(req, res, handler(audience, "placeholder"))).isTrue();

        // An admin's access never depends on a license tier — the tier is never even read.
        verify(licenseService, never()).effectiveTier();
    }

    // ------------------------------------------------------------------
    // Offer-key override: a redeemed Diamond override lifts a low base tier through a DIAMOND gate,
    // because the gate consults the effective tier (which already folds in the override).
    // ------------------------------------------------------------------

    @Test
    void overrideToDiamond_reachesDiamondFeature() {
        // The user's base tier is irrelevant here — the gate only ever sees the effective tier, which
        // an active offer-key override has raised to DIAMOND.
        assertThat(preHandleAs(LicenseTier.DIAMOND, handler(aggregate, "topSpreaders"))).isTrue();
        assertThat(preHandleAs(LicenseTier.DIAMOND, handler(report, "getMarketingReport"))).isTrue();
        assertThat(preHandleAs(LicenseTier.DIAMOND, handler(audience, "placeholder"))).isTrue();
    }

    // ------------------------------------------------------------------
    // End-to-end: the rejection renders as the structured 403 { feature, requiredTier }
    // ------------------------------------------------------------------

    @Test
    void belowTier_rendersStructured403Body() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(checkpoints)
                .addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        when(entityAccess.currentUserIsAdmin()).thenReturn(false);
        when(licenseService.effectiveTier()).thenReturn(LicenseTier.BRONZE);

        mvc.perform(get("/api/checkpoints/entity/{id}", 7L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.feature").value("Checkpoints"))
                .andExpect(jsonPath("$.requiredTier").value("SILVER"));
    }
}
