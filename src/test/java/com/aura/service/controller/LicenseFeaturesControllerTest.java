package com.aura.service.controller;

import com.aura.service.enums.LicenseTier;
import com.aura.service.service.EntitlementService;
import com.aura.service.service.EntitlementServiceImpl;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.LicenseService;
import com.aura.service.service.PreviewMaskingServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/license/features renders the whole catalog with per-user entitlement so the UI can show
 * every feature with a lock badge. Price-free: no cost field anywhere.
 */
class LicenseFeaturesControllerTest {

    private final LicenseService licenseService = mock(LicenseService.class);
    private final EntityAccessService entityAccess = mock(EntityAccessService.class);

    private MockMvc mvc() {
        EntitlementService entitlement = new EntitlementServiceImpl(
                licenseService, entityAccess, new PreviewMaskingServiceImpl());
        return MockMvcBuilders.standaloneSetup(new LicenseFeaturesController(entitlement)).build();
    }

    @Test
    void listsAllFeaturesWithEntitlementForSilverUser() throws Exception {
        when(entityAccess.currentUserIsAdmin()).thenReturn(false);
        when(licenseService.effectiveTier()).thenReturn(LicenseTier.SILVER);

        mvc().perform(get("/api/license/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                // Order follows the Feature enum: checkpoints, crisis, audience-content, intelligence-report, aggregated-intel.
                .andExpect(jsonPath("$[0].key").value("checkpoints"))
                .andExpect(jsonPath("$[0].name").value("Checkpoints"))
                .andExpect(jsonPath("$[0].requiredTier").value("SILVER"))
                .andExpect(jsonPath("$[0].entitled").value(true))   // SILVER >= SILVER
                .andExpect(jsonPath("$[1].key").value("crisis"))
                .andExpect(jsonPath("$[1].entitled").value(false))  // SILVER < GOLD
                .andExpect(jsonPath("$[3].key").value("intelligence-report"))
                .andExpect(jsonPath("$[3].entitled").value(false))  // SILVER < DIAMOND
                // Price-free: no per-feature cost is ever exposed.
                .andExpect(jsonPath("$[0].price").doesNotExist())
                .andExpect(jsonPath("$[1].price").doesNotExist());
    }

    @Test
    void adminIsEntitledToEveryFeature() throws Exception {
        when(entityAccess.currentUserIsAdmin()).thenReturn(true);

        mvc().perform(get("/api/license/features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entitled").value(true))
                .andExpect(jsonPath("$[1].entitled").value(true))
                .andExpect(jsonPath("$[2].entitled").value(true))
                .andExpect(jsonPath("$[3].entitled").value(true))
                .andExpect(jsonPath("$[4].entitled").value(true));
    }
}
