package com.aura.service.service;

import com.aura.service.enums.AnchorType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for the 4 anchor-typology options relevant to the
 * {@link com.aura.service.enums.CheckpointStage#ANCHOR_SEED} stage. A movie should have 2-3 of these
 * selected to gain traction before wide awareness (enforced as a v1 recommendation rule by
 * {@link CheckpointRecommendationService}, not by this catalog).
 *
 * <p>{@code function}/{@code barrierAddressed}/{@code example} copy is draft reference text pending a
 * marketing content pass - each entry's category name is product-specified, the supporting text is not.
 */
public final class AnchorTypeCatalog {

    public record AnchorTypeDefinition(
            AnchorType type,
            String name,
            String function,
            String barrierAddressed,
            String example) {
    }

    private static final Map<AnchorType, AnchorTypeDefinition> CATALOG = buildCatalog();

    private AnchorTypeCatalog() {
    }

    public static Map<AnchorType, AnchorTypeDefinition> all() {
        return CATALOG;
    }

    public static AnchorTypeDefinition byType(AnchorType type) {
        return CATALOG.get(type);
    }

    private static Map<AnchorType, AnchorTypeDefinition> buildCatalog() {
        Map<AnchorType, AnchorTypeDefinition> catalog = new LinkedHashMap<>();
        catalog.put(AnchorType.CASTING_INFLUENCER, new AnchorTypeDefinition(
                AnchorType.CASTING_INFLUENCER, "Casting / Influencer",
                "Imports a pre-existing, highly engaged digital audience",
                "Uncertainty - gives the audience a known, trusted entity",
                "Parithabangal Gopi cast in Anbe Diana"));
        catalog.put(AnchorType.PHYSICAL_ENGINEERING_ASSET, new AnchorTypeDefinition(
                AnchorType.PHYSICAL_ENGINEERING_ASSET, "Physical / Engineering Asset",
                "Generates earned media in non-entertainment sectors (auto, tech)",
                "Reactance - hides the pitch inside a genuine innovation, not an ad",
                "The \"Bujji\" vehicle in Kalki 2898 AD"));
        catalog.put(AnchorType.ESTABLISHED_IP_DIRECTOR, new AnchorTypeDefinition(
                AnchorType.ESTABLISHED_IP_DIRECTOR, "Established IP / Director",
                "Leverages historical success and existing world-building",
                "Distance - audience already understands the rules of the world, no explanation needed",
                "Franchise sequels; auteur-driven tentpoles"));
        catalog.put(AnchorType.VIRAL_BEHIND_THE_SCENES, new AnchorTypeDefinition(
                AnchorType.VIRAL_BEHIND_THE_SCENES, "Viral Behind-the-Scenes",
                "Humanizes the production, creates parasocial investment",
                "Corroborating Evidence - shows the makers' own passion, which reads as more credible "
                        + "than marketing copy",
                "Grassroots viral clip strategy (e.g. Marty Supreme BTS video)"));
        return Collections.unmodifiableMap(catalog);
    }
}
