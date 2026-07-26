package com.aura.service.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the box-office prediction factor catalog - number, name, direction,
 * and the [low, high] fractional impact range (e.g. 0.15 to 0.25, not 15 to 25) each factor is
 * allowed to contribute. {@link BoxOfficeBacktestWorkerImpl} generates the LLM prompt's factor
 * listing from this class and remaps each returned 1-5 rating back into a delta using the same
 * numbers, so the two can never drift apart the way a hand-maintained prompt-text catalog would.
 *
 * <p>Every factor is also tagged with a {@link Role} that determines how it enters the prediction
 * formula:
 * <ul>
 *   <li>{@link Role#COMPOUNDING} - rated by the LLM (1-5 or "NA"), remapped to a delta, and
 *       multiplied into the compounding product {@code Y = B0 * PROD(1 + delta_i)}.
 *   <li>{@link Role#SERVER_COMPUTED} - also multiplied into the compounding product, but the
 *       delta is computed directly from real dates/columns in {@code movies_data_collection}
 *       instead of an LLM rating (factors 46, 47 - teaser/trailer and first-single timing).
 *   <li>{@link Role#POST_RELEASE} - Category 8 (91-100). These describe outcomes of release
 *       (word-of-mouth, review scores, social discourse), so using them to predict the same
 *       movie's gross would be circular. Still rated by the LLM so it can populate the "Post
 *       Release Factors" advisory lists, but excluded from the compounding product entirely.
 *   <li>{@link Role#BASELINE_ONLY} - GDP (101) and inflation (102). Now handled structurally by
 *       the present-value budget adjustment in the baseline formula (B0), so including them again
 *       as compounding deltas would double-count the same macro adjustment. Not shown to the LLM.
 * </ul>
 */
public final class BoxOfficeFactorCatalog {

    public enum Direction { POSITIVE, NEGATIVE, BIDIRECTIONAL }

    public enum Role { COMPOUNDING, SERVER_COMPUTED, POST_RELEASE, BASELINE_ONLY }

    public record FactorDefinition(int number, String name, Direction direction, double low, double high, Role role) {

        /** Remaps a 1-5 rating to this factor's delta via the same affine formula used by
         *  ConflictBalanceServiceImpl/NarrativeNoveltyServiceImpl: (rating-1)/4 into [low, high]. */
        public double deltaForRating(int rating) {
            double normalized = (rating - 1) / 4.0;
            return low + normalized * (high - low);
        }
    }

    private static final Map<Integer, FactorDefinition> CATALOG = buildCatalog();

    private BoxOfficeFactorCatalog() {
    }

    public static Map<Integer, FactorDefinition> all() {
        return CATALOG;
    }

    public static FactorDefinition byNumber(int number) {
        return CATALOG.get(number);
    }

    public static List<FactorDefinition> byRole(Role role) {
        List<FactorDefinition> result = new ArrayList<>();
        for (FactorDefinition def : CATALOG.values()) {
            if (def.role() == role) {
                result.add(def);
            }
        }
        return result;
    }

    /** Factors the LLM is asked to rate: COMPOUNDING + POST_RELEASE (everything except the two
     *  server-computed timing factors and the two baseline-only macro factors). */
    public static List<FactorDefinition> llmRated() {
        List<FactorDefinition> result = new ArrayList<>();
        for (FactorDefinition def : CATALOG.values()) {
            if (def.role() == Role.COMPOUNDING || def.role() == Role.POST_RELEASE) {
                result.add(def);
            }
        }
        return result;
    }

    private static Map<Integer, FactorDefinition> buildCatalog() {
        Map<Integer, FactorDefinition> catalog = new LinkedHashMap<>();
        add(catalog, 1, "Protagonist-Antagonist Conflict Balance", Direction.POSITIVE, 0.250, 0.350);
        add(catalog, 2, "High-Concept Narrative Novelty", Direction.POSITIVE, 0.300, 0.450);
        add(catalog, 4, "Genre Template Adherence vs. Subversion", Direction.BIDIRECTIONAL, -0.200, 0.200);
        add(catalog, 11, "Romantic Track Integration", Direction.BIDIRECTIONAL, -0.150, 0.150);
        add(catalog, 15, "Twist Effectiveness and Unpredictability", Direction.POSITIVE, 0.200, 0.300);
        add(catalog, 16, "Star-to-Character Persona Fit", Direction.BIDIRECTIONAL, -0.400, 0.400);
        add(catalog, 17, "Core Fanbase Mobilization Value", Direction.POSITIVE, 0.300, 0.500);
        add(catalog, 18, "Lead Actor Screen Chemistry", Direction.POSITIVE, 0.200, 0.350);
        add(catalog, 19, "Support Cast Performance Credibility", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 20, "Directorial Brand Equity", Direction.POSITIVE, 0.250, 0.400);
        add(catalog, 21, "Anti-Hero Appeal and Moral Ambiguity", Direction.POSITIVE, 0.200, 0.300);
        add(catalog, 22, "Off-Screen Actor Controversy", Direction.BIDIRECTIONAL, -0.350, 0.350);
        add(catalog, 23, "Star Satiation and Screen Overexposure", Direction.NEGATIVE, -0.250, -0.150);
        add(catalog, 24, "Off-Script Event Speech Impact", Direction.BIDIRECTIONAL, -0.150, 0.150);
        add(catalog, 25, "Lead Actor Vulnerability and Range", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 26, "Multi-Generational Appeal of the Star", Direction.POSITIVE, 0.250, 0.350);
        add(catalog, 27, "Miscasting and Role Incongruence", Direction.NEGATIVE, -0.350, -0.200);
        add(catalog, 28, "Nostalgic Screen Reunions", Direction.POSITIVE, 0.200, 0.300);
        add(catalog, 29, "Star Political Aspirations / Dialogue Placement", Direction.BIDIRECTIONAL, -0.200, 0.200);
        add(catalog, 30, "Cameo Appearances of Iconic Stars", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 31, "Technical Quality of Visual Effects (VFX)", Direction.BIDIRECTIONAL, -0.300, 0.300);
        add(catalog, 32, "Immersive Sound Design and Mixing", Direction.POSITIVE, 0.100, 0.200);
        add(catalog, 33, "Action Sequence Choreography Innovation", Direction.POSITIVE, 0.200, 0.350);
        add(catalog, 34, "Background Score (BGM) Impact", Direction.POSITIVE, 0.250, 0.400);
        add(catalog, 35, "Production Design and Architectural Scale", Direction.POSITIVE, 0.200, 0.300);
        add(catalog, 36, "Realistic Color Grading and Cinematography", Direction.POSITIVE, 0.100, 0.200);
        add(catalog, 37, "Excessive Runtime and Editing Lag", Direction.NEGATIVE, -0.300, -0.150);
        add(catalog, 38, "Dynamic Editing and Transition Pacing", Direction.POSITIVE, 0.100, 0.150);
        add(catalog, 39, "Authenticity of Period / Cultural Setting", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 40, "Budget-to-Scale Efficiency", Direction.BIDIRECTIONAL, -0.200, 0.200);
        add(catalog, 41, "Use of Animation for Complex Flashbacks", Direction.POSITIVE, 0.100, 0.150);
        add(catalog, 42, "Excessive and Intrusive Song Placements", Direction.NEGATIVE, -0.250, -0.150);
        add(catalog, 43, "Location Novelty and Aesthetic Variety", Direction.POSITIVE, 0.100, 0.150);
        add(catalog, 44, "Live Action over Heavy Green-Screen", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 45, "Overuse of Graphic / Gratuitous Violence", Direction.BIDIRECTIONAL, -0.150, 0.150);
        addServerComputed(catalog, 46, "Teaser and Trailer Impact", Direction.POSITIVE, 0.350, 0.500);
        addServerComputed(catalog, 47, "Timing of First Single Release", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 48, "Use of Brand Extensions / Sequel Names", Direction.BIDIRECTIONAL, -0.300, 0.300);
        add(catalog, 49, "Viral Music and Social Media Audio Trends", Direction.POSITIVE, 0.200, 0.350);
        add(catalog, 50, "Pre-Release Promotional Controversies", Direction.BIDIRECTIONAL, -0.150, 0.150);
        add(catalog, 51, "Star Attendance at On-Ground Events", Direction.POSITIVE, 0.100, 0.200);
        add(catalog, 52, "Micro-Video Social Media Campaigns", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 53, "Influencer-Driven Promotions", Direction.POSITIVE, 0.100, 0.150);
        add(catalog, 54, "Misleading Trailer Marketing", Direction.NEGATIVE, -0.400, -0.250);
        add(catalog, 55, "High-Definition Promo / BTS Content", Direction.POSITIVE, 0.100, 0.150);
        add(catalog, 56, "Strategic Use of Countdown Posters", Direction.POSITIVE, 0.050, 0.100);
        add(catalog, 57, "Excessive / Over-Saturated Marketing", Direction.NEGATIVE, -0.150, -0.100);
        add(catalog, 58, "Cross-Promotion and Brand Partnerships", Direction.POSITIVE, 0.100, 0.200);
        add(catalog, 59, "Dynamic Pre-Release Ticket Pricing", Direction.BIDIRECTIONAL, -0.150, 0.150);
        add(catalog, 60, "Global Promotional Tours", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 61, "Holiday Release Windows", Direction.POSITIVE, 0.400, 0.600);
        add(catalog, 62, "Direct Box Office Clashes", Direction.NEGATIVE, -0.350, -0.200);
        add(catalog, 63, "Student Examination Schedules", Direction.NEGATIVE, -0.250, -0.150);
        add(catalog, 64, "Political Events and Elections", Direction.NEGATIVE, -0.400, -0.200);
        add(catalog, 65, "Major Sporting Events (e.g., IPL)", Direction.NEGATIVE, -0.200, -0.100);
        add(catalog, 66, "Academic Summer Vacation Windows", Direction.POSITIVE, 0.250, 0.350);
        add(catalog, 67, "Extreme Weather Conditions", Direction.NEGATIVE, -0.150, -0.100);
        add(catalog, 68, "Theatrical Window / OTT Release Strategy", Direction.BIDIRECTIONAL, -0.200, 0.200);
        add(catalog, 69, "Post-Clash Spillover Audience", Direction.POSITIVE, 0.100, 0.150);
        add(catalog, 70, "Re-Release Timing and Nostalgia", Direction.POSITIVE, 0.100, 0.200);
        add(catalog, 71, "CBFC Rating Classifications (U vs. UA/A)", Direction.BIDIRECTIONAL, -0.300, 0.300);
        add(catalog, 72, "Multi-State Political / Cultural Bans", Direction.NEGATIVE, -0.500, -0.300);
        add(catalog, 73, "High-Definition Pre-Release Leak", Direction.NEGATIVE, -0.800, -0.600);
        add(catalog, 74, "Legal Disputes over Title Ownership", Direction.NEGATIVE, -0.250, -0.150);
        add(catalog, 75, "Copyright Claims on Visuals / Audio", Direction.NEGATIVE, -0.350, -0.200);
        add(catalog, 76, "Regional Entertainment Tax Exemptions", Direction.POSITIVE, 0.150, 0.300);
        add(catalog, 77, "Inter-State Distribution Disputes", Direction.NEGATIVE, -0.300, -0.150);
        add(catalog, 78, "Plagiarism Allegations and Remake Laws", Direction.BIDIRECTIONAL, -0.150, 0.150);
        add(catalog, 79, "Real-Life Personality Name Similarities", Direction.NEGATIVE, -0.200, -0.100);
        add(catalog, 80, "Administrative Delays in Certifications", Direction.NEGATIVE, -0.400, -0.200);
        add(catalog, 81, "Digital Key Delivery Message (KDM) Lockout", Direction.NEGATIVE, -0.600, -0.400);
        add(catalog, 82, "Minimum Guarantee (MG) Distribution", Direction.POSITIVE, 0.200, 0.350);
        add(catalog, 83, "Outright Purchase Territorial Sales", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 84, "High Interest Rates on Film Finance", Direction.NEGATIVE, -0.300, -0.150);
        add(catalog, 85, "Multiplex Revenue Share Splits", Direction.BIDIRECTIONAL, -0.200, 0.200);
        add(catalog, 86, "Global Subtitle / Dubbing Quality", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 87, "Screen Count Allocation and Show Pacing", Direction.POSITIVE, 0.250, 0.400);
        add(catalog, 88, "Print & Advertising (P&A) Commitments", Direction.POSITIVE, 0.200, 0.300);
        add(catalog, 89, "Joint Production Partnerships", Direction.POSITIVE, 0.150, 0.250);
        add(catalog, 90, "Producer Debt and Studio Solvency", Direction.NEGATIVE, -0.450, -0.250);
        addPostRelease(catalog, 91, "Organic Word-of-Mouth (Post-Day 1)", Direction.BIDIRECTIONAL, -0.500, 0.500);
        addPostRelease(catalog, 92, "Social Media Discourse and Meme Trends", Direction.BIDIRECTIONAL, -0.250, 0.250);
        addPostRelease(catalog, 93, "Target Audience Alignment (Families vs. Youth)", Direction.BIDIRECTIONAL, -0.300, 0.300);
        addPostRelease(catalog, 94, "Critical Review Ratings on Aggregators", Direction.BIDIRECTIONAL, -0.150, 0.150);
        addPostRelease(catalog, 95, "Fast-Tracked Online Ticket Booking Trends", Direction.POSITIVE, 0.200, 0.300);
        addPostRelease(catalog, 96, "Sensitivity of Cultural / Religious Portrayals", Direction.BIDIRECTIONAL, -0.250, 0.250);
        addPostRelease(catalog, 97, "Audience Fatigue with Repetitive Templates", Direction.NEGATIVE, -0.450, -0.300);
        addPostRelease(catalog, 98, "Theatrical Communal Viewing Experience", Direction.POSITIVE, 0.200, 0.350);
        addPostRelease(catalog, 99, "Value-for-Money Perception of Tickets", Direction.BIDIRECTIONAL, -0.150, 0.150);
        addPostRelease(catalog, 100, "Repeat Theatrical Viewership Value", Direction.POSITIVE, 0.250, 0.400);
        addBaselineOnly(catalog, 101, "Indian GDP", Direction.POSITIVE, 0.200, 0.400);
        addBaselineOnly(catalog, 102, "Inflation Rate", Direction.BIDIRECTIONAL, -0.150, 0.150);
        add(catalog, 103, "Size of the Market", Direction.POSITIVE, 0.300, 1.500);
        return Collections.unmodifiableMap(catalog);
    }

    private static void add(Map<Integer, FactorDefinition> catalog, int number, String name, Direction direction, double low, double high) {
        catalog.put(number, new FactorDefinition(number, name, direction, low, high, Role.COMPOUNDING));
    }

    private static void addServerComputed(Map<Integer, FactorDefinition> catalog, int number, String name, Direction direction, double low, double high) {
        catalog.put(number, new FactorDefinition(number, name, direction, low, high, Role.SERVER_COMPUTED));
    }

    private static void addPostRelease(Map<Integer, FactorDefinition> catalog, int number, String name, Direction direction, double low, double high) {
        catalog.put(number, new FactorDefinition(number, name, direction, low, high, Role.POST_RELEASE));
    }

    private static void addBaselineOnly(Map<Integer, FactorDefinition> catalog, int number, String name, Direction direction, double low, double high) {
        catalog.put(number, new FactorDefinition(number, name, direction, low, high, Role.BASELINE_ONLY));
    }
}
