package com.aura.service.service;

import com.aura.service.dto.HourlyActivityResponse;
import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.dto.RecommendedActionUser;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.EntityLanguageSpreaderSnapshot;
import com.aura.service.entity.EntityViralSeedSnapshot;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.MobilizeAction;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.enums.Sentiment;
import com.aura.service.enums.TimePeriod;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.EntityLanguageSpreaderSnapshotRepository;
import com.aura.service.repository.EntityViralSeedSnapshotRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.MobilizeActionRepository;
import com.aura.service.service.TopSpreaderLookupService.SpreaderProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 1 candidate-generation logic for the "Recommended Actions" Command Center panel: every
 * number here (category, confidencePct, timing window) is computed from real
 * {@code movies_data_collection} queries, this platform's own mention/spreader/hourly-activity data,
 * or plain calendar math against {@link BoxOfficeFactorCatalog}'s calibrated constants - never asked
 * of an LLM. A factor with insufficient real backing data for this entity simply produces no
 * candidate - except {@link #lowOnlinePresenceCandidate}, where a near-zero tracked-mention count is
 * itself the grounding signal (absence of online presence, not absence of data about it). No
 * candidate here requires a budget on file: {@link #comparableBudgetCandidates} falls back to
 * genre+language comps across every budget tier when the entity has none, and
 * {@link #genreAudienceReachCandidate} (backed by {@link GenreMarketingLookupService}'s AuraMath
 * genre-audience lookup), {@link #movieBuffOutreachCandidate}, and {@link #viralSeedCandidate}
 * (both backed by further AuraMath keyword-scoped lookups) never needed one in the first place - this
 * platform's actual data skews toward small/independent productions with no budget recorded, and
 * candidate generation should still reach them.
 *
 * <p>Reuses (never re-derives) {@link BoxOfficeFactorCatalog} for factor names/impact ranges, the
 * exact teaser/trailer/first-single calendar thresholds from {@link BoxOfficeBacktestWorkerImpl},
 * the budget-range pattern from {@link MovieAudienceServiceImpl}, and the evangelist
 * positive-sentiment-filter logic from {@link MobilizeAlliesService} (duplicated here rather than
 * called, since that service returns a differently-shaped, DM-generating response - see
 * {@link #filterPredominantlyPositive} for the mirrored logic, kept in sync by comment reference).
 * Evangelist/ally ranking is keyed on AuraMath's {@code total_views} (a real reach proxy the
 * top-50-spreaders endpoint actually returns), not on {@code influenceTier} - that endpoint never
 * emits it, see {@link TopSpreaderLookupService.SpreaderProfile}. {@link #tierRank} is still used,
 * but only by {@link #movieBuffOutreachCandidate}, whose AuraMath endpoint does emit a tier.
 *
 * <p>{@link #peerMarketingTacticCandidates} is the one generator not grounded in
 * {@link BoxOfficeFactorCatalog} at all: it cites real, verbatim marketing tactics other genre-comparable
 * movies actually ran, sourced from {@link MovieMarketingTacticsQueryService} (joined to
 * {@code movies_data_collection} for genre, since {@code movie_marketing_tactics} carries none of its
 * own) - see that method's own doc for why category/confidence are derived differently there. It also
 * splits its output into "rare" (always surfaced) and "common" candidates - a tactic nearly every
 * comparable movie ran (a teaser/trailer release, say) is withheld by default and only added back in
 * {@link #buildCandidateActions} as a fallback for a movie whose plan is otherwise too thin, still
 * pre-release, and for which tracked posts don't already evidence the tactic - see
 * {@link #isCommonTactic}/{@link #alreadyPostedTacticSignal}.
 *
 * <p>{@link #generateNonObviousLeverCandidates} and {@link #generatePlaybookCandidates} are likewise not
 * grounded in {@link BoxOfficeFactorCatalog}: they read AuraMath's F5/F7 statistical-mining tables
 * ({@link NonObviousLeverLookupService}, {@link PlaybookLookupService}) and carry their p-value/FDR
 * q-value/sample-size evidence as {@link com.aura.service.dto.RecommendedActionCandidate.StatisticalEvidence}
 * rather than a prose {@code supportingFacts} sentence - Phase 2 phrases it. Both withhold any finding at
 * or above {@link #STATISTICAL_EVIDENCE_Q_VALUE_BAR}, the same "don't surface what didn't clear a
 * threshold" convention as every comps/sample-size gate above.
 *
 * <p>{@link #topSpreaderGapCandidates} reads {@link EntityLanguageSpreaderSnapshot} rows populated by
 * {@link TopSpreaderLanguageSyncService}'s periodic (every 2 days) AuraMath top-50-spreaders sweep -
 * one candidate per language this movie is actually being marketed in, comparing its own spreader count
 * against the best-covered budget-comparable movie for that same language. Unlike
 * {@link #comparableBudgetCandidates}, a missing/undisclosed budget on this movie skips the candidate
 * entirely rather than falling back to an unscoped comparison - "of similar budget" is the whole point
 * of this candidate. See {@link #hasRealBudget} for the {@code 404}-as-undisclosed sentinel convention.
 *
 * <p>{@link #viralSeedViewCountGapCandidate} is the budget-comparable sibling of
 * {@link #topSpreaderGapCandidates}: it compares this movie's own cumulative view count (summed
 * across all four tracked platforms, see {@link MentionRepository#findTotalViewsForEntity}) against
 * budget-comparable movies', and when one or more are meaningfully ahead, offers that movie's
 * {@link EntityViralSeedSnapshot} (populated by {@link ViralSeedSyncService}'s periodic AuraMath
 * viral-seeds sweep) as outreach targets who haven't already commented on this movie.
 */
@Service
public class RecommendedActionCandidateServiceImpl implements RecommendedActionCandidateService {

    // ---- Category thresholds, applied to a factor's BoxOfficeFactorCatalog impact-range midpoint
    // (|low|+|high|)/2 ----
    static final double HIGH_IMPACT_THRESHOLD = 0.25;
    static final double MEDIUM_IMPACT_THRESHOLD = 0.12;

    // ---- SERVER_COMPUTED / calendar-math confidence: deterministic, not a statistical estimate ----
    static final int SERVER_COMPUTED_CONFIDENCE = 90;

    // ---- movies_data_collection comps-backed confidence, tiered by comparable-movie sample size.
    // Fewer than COMPS_TIER_MIN_SAMPLE comps means the candidate is not produced at all. ----
    static final long COMPS_TIER_MIN_SAMPLE = 5;
    static final long COMPS_TIER_MID_SAMPLE = 15;
    static final long COMPS_TIER_HIGH_SAMPLE = 30;
    static final int COMPS_CONFIDENCE_LOW = 55;
    static final int COMPS_CONFIDENCE_MID = 70;
    static final int COMPS_CONFIDENCE_HIGH = 85;

    // ---- Evangelist-backed confidence, tiered by qualifying (predominantly-positive) account count.
    // Zero qualifying accounts means the candidate is not produced at all. ----
    static final long EVANGELIST_TIER_MIN = 1;
    static final long EVANGELIST_TIER_MID = 4;
    static final long EVANGELIST_TIER_HIGH = 8;
    static final int EVANGELIST_CONFIDENCE_LOW = 50;
    static final int EVANGELIST_CONFIDENCE_MID = 65;
    static final int EVANGELIST_CONFIDENCE_HIGH = 80;

    // ---- Peak-audience-hour confidence, tiered by the total active-user sample size backing the
    // hourly distribution. Fewer than HOURLY_TIER_MIN active users means the candidate is not
    // produced at all. ----
    static final long HOURLY_TIER_MIN = 20;
    static final long HOURLY_TIER_MID = 100;
    static final long HOURLY_TIER_HIGH = 500;
    static final int HOURLY_CONFIDENCE_LOW = 50;
    static final int HOURLY_CONFIDENCE_MID = 65;
    static final int HOURLY_CONFIDENCE_HIGH = 80;
    static final int TOP_PEAK_HOURS = 3;

    // ---- Post-day-1 word-of-mouth confidence, tiered by real mention volume tracked in the
    // factor's own [7,28]-days-post-release window. Fewer than WORD_OF_MOUTH_TIER_MIN mentions in
    // that window means the candidate is not produced at all - this only fires once real
    // post-release conversation actually exists to measure, not before. ----
    static final long WORD_OF_MOUTH_TIER_MIN = 10;
    static final long WORD_OF_MOUTH_TIER_MID = 50;
    static final long WORD_OF_MOUTH_TIER_HIGH = 200;
    static final int WORD_OF_MOUTH_CONFIDENCE_LOW = 55;
    static final int WORD_OF_MOUTH_CONFIDENCE_MID = 70;
    static final int WORD_OF_MOUTH_CONFIDENCE_HIGH = 85;

    // ---- Low-online-presence candidate: fires when total tracked mentions (all-time, any sentiment)
    // fall below this floor - the one generator where absence of engagement data is the signal itself,
    // not a reason to stay silent. Fixed (not tiered) confidence since there's no larger sample to
    // climb toward; the window is deliberately wide (a full year of pre-release runway) rather than
    // pulled from WINDOW_BY_FACTOR, since "start building visibility" is valid on any given day this
    // condition holds, not just a narrow marketing-calendar slice. ----
    static final long LOW_PRESENCE_MENTION_THRESHOLD = 25;
    static final int LOW_PRESENCE_CONFIDENCE = 65;
    static final int LOW_PRESENCE_WINDOW_START_DAYS = -365;
    static final int LOW_PRESENCE_WINDOW_END_DAYS = -14;

    // ---- Genre audience-reach confidence: fixed, grounded in a live AuraMath lookup rather than a
    // larger-sample-means-more-confidence tier. This is the one candidate that needs no budget figure
    // at all - see genreAudienceReachCandidate. ----
    static final int GENRE_REACH_CONFIDENCE = 70;

    // ---- Factor 46/47 calendar thresholds - mirror BoxOfficeBacktestWorkerImpl's constants exactly
    // (kept duplicated, not shared, since that class's constants are private); see that class for the
    // calibration rationale. ----
    static final int SHORT_WINDOW_DAYS = 14;
    static final double SHORT_WINDOW_PENALTY = -0.15;
    static final int OPTIMAL_MIN_DAYS_46 = 30;
    static final int OPTIMAL_MAX_DAYS_46 = 45;
    static final double OPTIMAL_BONUS_46 = 0.25;
    static final int OPTIMAL_MIN_DAYS_47 = 42;
    static final int OPTIMAL_MAX_DAYS_47 = 56;
    static final double OPTIMAL_BONUS_47 = 0.25;

    // Fraction of budget used to build the comparable-movie range [0.5x, 1.5x] - mirrors
    // MovieAudienceServiceImpl.BUDGET_RANGE_FRACTION.
    static final double BUDGET_RANGE_FRACTION = 0.5;

    // A window whose two offsets straddle release by no more than this many days renders as
    // "Release week" rather than a day/week count.
    static final int RELEASE_WEEK_SPAN_DAYS = 7;

    // A release date within this many days of a known holiday counts as a holiday-window release.
    static final int HOLIDAY_PROXIMITY_DAYS = 14;

    // Before/after window (each side) used to measure the mention-volume lift a historical
    // MobilizeAction correlated with.
    static final long ALLY_LIFT_WINDOW_DAYS = 7;
    static final int MIN_MOBILIZE_EVENTS_FOR_LIFT = 3;

    private static final String MOVIE_TYPE = "MOVIE";

    // ---- BoxOfficeFactorCatalog factor numbers this service is grounded in ----
    static final int FACTOR_FANBASE_MOBILIZATION = 17;
    static final int FACTOR_MICRO_VIDEO_CAMPAIGNS = 52;
    static final int FACTOR_INFLUENCER_PROMOTIONS = 53;
    static final int FACTOR_TEASER_TRAILER = 46;
    static final int FACTOR_FIRST_SINGLE = 47;
    static final int FACTOR_HOLIDAY_RELEASE_WINDOWS = 61;
    static final int FACTOR_SCREEN_COUNT = 87;
    static final int FACTOR_PA_COMMITMENTS = 88;
    static final int FACTOR_ORGANIC_WORD_OF_MOUTH = 91;

    // ---- Movie-buff / viral-seed outreach confidence: fixed, grounded in a live AuraMath
    // lookup rather than a larger-sample-means-more-confidence tier - same reasoning as
    // GENRE_REACH_CONFIDENCE. ----
    static final int MOVIE_BUFF_CONFIDENCE = 65;
    static final int VIRAL_SEED_CONFIDENCE = 65;

    // Cap on how many real account handles ride along in a candidate's exampleHandles - enough for
    // marketing to have concrete names to act on without turning the response into a full roster dump.
    static final int TOP_HANDLES_LIMIT = 3;

    // Cap on how many real accounts ride along in a candidate's relevantUsers - the fuller "View
    // Details" roster a marketing team can page through, one tier up from exampleHandles' short
    // inline-text sample without turning the response into an unbounded full roster dump.
    static final int MAX_RELEVANT_USERS = 20;

    // ---- Peer marketing-tactic confidence: tiered by distinct comp-movie count backing a given
    // (main, sub) classification bucket. Deliberately low-N thresholds compared to compsConfidence's
    // 5/15/30 - a single real, quoted, verifiable tactic from one comparable movie is still concrete,
    // actionable evidence (the whole point of this generator), just weaker than a statistical
    // aggregate over dozens of releases. ----
    static final long PEER_TACTIC_TIER_MIN = 1;
    static final long PEER_TACTIC_TIER_MID = 3;
    static final long PEER_TACTIC_TIER_HIGH = 6;
    static final int PEER_TACTIC_CONFIDENCE_LOW = 50;
    static final int PEER_TACTIC_CONFIDENCE_MID = 62;
    static final int PEER_TACTIC_CONFIDENCE_HIGH = 75;

    // Cap on how many real comp-movie citations ride along in one peer-tactic candidate's
    // supportingFacts - enough for the LLM (and marketing) to see real precedent without flooding the
    // candidate with every comp ever recorded for that classification bucket.
    static final int TACTIC_EXAMPLES_PER_CANDIDATE_LIMIT = 3;

    // movie_marketing_tactics tracks no execution-date column, so no narrower window than a broad
    // pre-release runway can be honestly claimed - same rationale as LOW_PRESENCE_WINDOW_*.
    static final int PEER_TACTIC_WINDOW_START_DAYS = -120;
    static final int PEER_TACTIC_WINDOW_END_DAYS = -1;

    // ---- Non-obvious lever / playbook-sequence: neither has a per-day execution window of its own
    // (a mined behavioral feature or checkpoint sequence, not a calendar fact), so both reuse the same
    // broad pre-release runway as PEER_TACTIC_WINDOW_*. A finding at or above STATISTICAL_EVIDENCE_Q_VALUE_BAR
    // never produces a candidate at all - the same "don't surface what didn't clear a threshold"
    // convention as compsConfidence/peerTacticConfidence. Confidence is tiered by FDR q-value itself
    // (lower = stronger evidence, so the tier comparisons run the opposite direction from every other
    // *_TIER_* threshold in this file), not by sample size - AuraMath's F5/F7 miners already fold sample
    // size into the q-value via multiple-testing correction. ----
    static final double STATISTICAL_EVIDENCE_Q_VALUE_BAR = 0.10;
    static final double STATISTICAL_EVIDENCE_Q_TIER_HIGH = 0.01;
    static final double STATISTICAL_EVIDENCE_Q_TIER_MID = 0.05;
    static final int STATISTICAL_EVIDENCE_CONFIDENCE_LOW = 55;
    static final int STATISTICAL_EVIDENCE_CONFIDENCE_MID = 70;
    static final int STATISTICAL_EVIDENCE_CONFIDENCE_HIGH = 85;

    // ---- Top-spreader language-coverage gap: tiered by how many budget-comparable movies had a
    // spreader snapshot for the language in question - a single real comparable movie is still
    // concrete, actionable evidence (same reasoning as PEER_TACTIC_TIER_*), just weaker than several. ----
    static final long SPREADER_GAP_TIER_MIN = 1;
    static final long SPREADER_GAP_TIER_MID = 3;
    static final long SPREADER_GAP_TIER_HIGH = 6;
    static final int SPREADER_GAP_CONFIDENCE_LOW = 50;
    static final int SPREADER_GAP_CONFIDENCE_MID = 65;
    static final int SPREADER_GAP_CONFIDENCE_HIGH = 80;

    // Minimum shortfall (best comparable movie's count minus this movie's own count) before the gap is
    // considered meaningful enough to surface - avoids flagging noise like "1 vs 2".
    static final int SPREADER_GAP_MIN_ABSOLUTE_SHORTFALL = 3;

    // ---- Cumulative view-count gap: tiered by how many budget-comparable movies have a meaningfully
    // higher cumulative view count than this movie - same reasoning/tiering as SPREADER_GAP_TIER_*. ----
    static final long VIEW_GAP_TIER_MIN = 1;
    static final long VIEW_GAP_TIER_MID = 3;
    static final long VIEW_GAP_TIER_HIGH = 6;
    static final int VIEW_GAP_CONFIDENCE_LOW = 50;
    static final int VIEW_GAP_CONFIDENCE_MID = 65;
    static final int VIEW_GAP_CONFIDENCE_HIGH = 80;

    // Minimum fractional view-count lead a comparable movie needs over this movie's own cumulative
    // view count before the gap is considered meaningful enough to surface - avoids flagging noise
    // like "1,010 vs 1,000".
    static final double VIEW_GAP_MIN_PCT_MORE_FRACTION = 0.15;

    // How many comparable movies' view-count lead get cited by name in one candidate - kept small
    // (like TOP_HANDLES_LIMIT) so the recommendation stays concrete rather than listing every
    // qualifying comp; also which of the qualifying comps get chosen is reselected once per day (see
    // viralSeedViewCountGapCandidate), so a periodic re-run surfaces a different real example over time.
    static final int VIEW_GAP_MAX_EXAMPLES = 2;

    // When a movie underperforms, production houses often decline to disclose its budget, and that
    // refusal is recorded as the literal value 404 in managed_entities.budget rather than left null.
    // hasRealBudget treats it identically to "no budget on file" everywhere in this generator.
    static final double UNDISCLOSED_BUDGET_SENTINEL = 404.0;

    // ---- Common-tactic filtering: a (main, sub) classification bucket run by most genre+language
    // peer movies (a teaser or trailer release, say) is something a marketing team already knows to
    // do without this platform prompting it - see peerMarketingTacticCandidates/isCommonTactic. Such
    // a bucket is withheld by default and only surfaced as a low-inventory fallback
    // (COMMON_TACTIC_FILLER_MIN_ACTIONS) when this movie otherwise has too few grounded actions, and
    // only pre-release - never useful once a movie has already released, and pointless if tracked
    // posts already show the tactic happened (see alreadyPostedTacticSignal).
    // COMMON_TACTIC_MIN_PEER_SAMPLE guards against a tiny peer pool (e.g. 2 comps that both happened
    // to run the same tactic) reading as "almost every movie does this" off too little evidence.
    static final double COMMON_TACTIC_PREVALENCE_THRESHOLD = 0.70;
    static final long COMMON_TACTIC_MIN_PEER_SAMPLE = 5;
    static final int COMMON_TACTIC_FILLER_MIN_ACTIONS = 3;

    // Generic marketing vocabulary stripped before matching a common tactic's classification name
    // against tracked post content (see tacticSignalKeywords/alreadyPostedTacticSignal) - without
    // this, a sub-classification like "Teaser Release" would search posts for the word "release",
    // which shows up in unrelated box-office/release-date chatter and would falsely read as the
    // tactic already having happened.
    private static final Set<String> TACTIC_KEYWORD_STOPWORDS = Set.of(
            "release", "releases", "released", "campaign", "campaigns", "promotion", "promotions",
            "promotional", "marketing", "content", "media", "social", "video", "launch", "launches",
            "event", "events", "activity", "activities", "drive", "reveal", "week", "date", "official",
            "movie", "film", "strategy", "digital", "online");

    private record WindowSpec(int startDays, int endDays) {
    }

    private record Holiday(String name, LocalDate date) {
    }

    // Typical marketing-industry execution windows for the curated factor list, authored once as a
    // developer lookup table (not computed per movie) per standard pre/post-release marketing
    // lead-time practice. Only the entries a Phase 1 generator actually uses are exercised by tests;
    // the rest fill out the full curated list for future generators to reuse.
    private static final Map<Integer, WindowSpec> WINDOW_BY_FACTOR = buildWindowTable();

    private static Map<Integer, WindowSpec> buildWindowTable() {
        Map<Integer, WindowSpec> table = new LinkedHashMap<>();
        table.put(FACTOR_FANBASE_MOBILIZATION, new WindowSpec(-21, -7));
        table.put(20, new WindowSpec(-60, -30));   // Directorial Brand Equity
        table.put(28, new WindowSpec(-45, -14));   // Nostalgic Screen Reunions
        table.put(30, new WindowSpec(-21, -7));    // Cameo Appearances of Iconic Stars
        table.put(48, new WindowSpec(-90, -60));   // Use of Brand Extensions / Sequel Names
        table.put(51, new WindowSpec(-30, -7));    // Star Attendance at On-Ground Events
        table.put(FACTOR_MICRO_VIDEO_CAMPAIGNS, new WindowSpec(-45, -1));
        table.put(53, new WindowSpec(-30, -7));    // Influencer-Driven Promotions
        table.put(55, new WindowSpec(-60, -14));   // High-Definition Promo / BTS Content
        table.put(56, new WindowSpec(-14, -1));    // Strategic Use of Countdown Posters
        table.put(58, new WindowSpec(-84, -56));   // Cross-Promotion and Brand Partnerships
        table.put(60, new WindowSpec(-56, -28));   // Global Promotional Tours
        table.put(FACTOR_HOLIDAY_RELEASE_WINDOWS, new WindowSpec(0, 0));
        table.put(FACTOR_SCREEN_COUNT, new WindowSpec(-70, -42));
        table.put(FACTOR_PA_COMMITMENTS, new WindowSpec(-70, -42));
        table.put(91, new WindowSpec(7, 28));      // Organic Word-of-Mouth (Post-Day 1)
        table.put(92, new WindowSpec(7, 28));      // Social Media Discourse and Meme Trends
        table.put(93, new WindowSpec(7, 21));      // Target Audience Alignment
        table.put(94, new WindowSpec(0, 7));       // Critical Review Ratings on Aggregators
        table.put(95, new WindowSpec(-14, 0));     // Fast-Tracked Online Ticket Booking Trends
        return Collections.unmodifiableMap(table);
    }

    // Approximate dates for major Indian festival/holiday windows relevant to theatrical release
    // timing. Fixed-date holidays repeat exactly; lunar/solar-calendar festivals (Diwali, Eid, Holi,
    // Onam, Dussehra, Sankranti's regional siblings) are hardcoded per year and should be extended as
    // new release years are onboarded.
    private static final List<Holiday> HOLIDAYS = List.of(
            new Holiday("Republic Day", LocalDate.of(2024, 1, 26)),
            new Holiday("Republic Day", LocalDate.of(2025, 1, 26)),
            new Holiday("Republic Day", LocalDate.of(2026, 1, 26)),
            new Holiday("Republic Day", LocalDate.of(2027, 1, 26)),
            new Holiday("Makar Sankranti / Pongal", LocalDate.of(2024, 1, 15)),
            new Holiday("Makar Sankranti / Pongal", LocalDate.of(2025, 1, 14)),
            new Holiday("Makar Sankranti / Pongal", LocalDate.of(2026, 1, 14)),
            new Holiday("Makar Sankranti / Pongal", LocalDate.of(2027, 1, 15)),
            new Holiday("Holi", LocalDate.of(2024, 3, 25)),
            new Holiday("Holi", LocalDate.of(2025, 3, 14)),
            new Holiday("Holi", LocalDate.of(2026, 3, 4)),
            new Holiday("Holi", LocalDate.of(2027, 3, 22)),
            new Holiday("Eid al-Fitr", LocalDate.of(2024, 4, 11)),
            new Holiday("Eid al-Fitr", LocalDate.of(2025, 3, 31)),
            new Holiday("Eid al-Fitr", LocalDate.of(2026, 3, 20)),
            new Holiday("Eid al-Fitr", LocalDate.of(2027, 3, 9)),
            new Holiday("Independence Day", LocalDate.of(2024, 8, 15)),
            new Holiday("Independence Day", LocalDate.of(2025, 8, 15)),
            new Holiday("Independence Day", LocalDate.of(2026, 8, 15)),
            new Holiday("Independence Day", LocalDate.of(2027, 8, 15)),
            new Holiday("Onam", LocalDate.of(2024, 9, 15)),
            new Holiday("Onam", LocalDate.of(2025, 9, 5)),
            new Holiday("Onam", LocalDate.of(2026, 8, 26)),
            new Holiday("Onam", LocalDate.of(2027, 9, 14)),
            new Holiday("Dussehra", LocalDate.of(2024, 10, 12)),
            new Holiday("Dussehra", LocalDate.of(2025, 10, 2)),
            new Holiday("Dussehra", LocalDate.of(2026, 10, 20)),
            new Holiday("Dussehra", LocalDate.of(2027, 10, 9)),
            new Holiday("Diwali", LocalDate.of(2024, 10, 31)),
            new Holiday("Diwali", LocalDate.of(2025, 10, 20)),
            new Holiday("Diwali", LocalDate.of(2026, 11, 8)),
            new Holiday("Diwali", LocalDate.of(2027, 10, 29)),
            new Holiday("Christmas", LocalDate.of(2024, 12, 25)),
            new Holiday("Christmas", LocalDate.of(2025, 12, 25)),
            new Holiday("Christmas", LocalDate.of(2026, 12, 25)),
            new Holiday("Christmas", LocalDate.of(2027, 12, 25))
    );

    private final ManagedEntityRepository entityRepository;
    private final MentionRepository mentionRepository;
    private final MobilizeActionRepository mobilizeActionRepository;
    private final TopSpreaderLookupService spreaderLookup;
    private final DashboardService dashboardService;
    private final MoviesDataCollectionQueryService moviesDataQueryService;
    private final GenreMarketingLookupService genreMarketingLookup;
    private final MovieBuffLookupService movieBuffLookup;
    private final ViralSeedLookupService viralSeedLookup;
    private final MovieMarketingTacticsQueryService tacticsQueryService;
    private final NonObviousLeverLookupService nonObviousLeverLookup;
    private final PlaybookLookupService playbookLookup;
    private final EntityLanguageSpreaderSnapshotRepository spreaderSnapshotRepository;
    private final EntityViralSeedSnapshotRepository viralSeedSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RecommendedActionCandidateServiceImpl(
            ManagedEntityRepository entityRepository,
            MentionRepository mentionRepository,
            MobilizeActionRepository mobilizeActionRepository,
            TopSpreaderLookupService spreaderLookup,
            DashboardService dashboardService,
            MoviesDataCollectionQueryService moviesDataQueryService,
            GenreMarketingLookupService genreMarketingLookup,
            MovieBuffLookupService movieBuffLookup,
            ViralSeedLookupService viralSeedLookup,
            MovieMarketingTacticsQueryService tacticsQueryService,
            NonObviousLeverLookupService nonObviousLeverLookup,
            PlaybookLookupService playbookLookup,
            EntityLanguageSpreaderSnapshotRepository spreaderSnapshotRepository,
            EntityViralSeedSnapshotRepository viralSeedSnapshotRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.entityRepository = entityRepository;
        this.mentionRepository = mentionRepository;
        this.mobilizeActionRepository = mobilizeActionRepository;
        this.spreaderLookup = spreaderLookup;
        this.dashboardService = dashboardService;
        this.moviesDataQueryService = moviesDataQueryService;
        this.genreMarketingLookup = genreMarketingLookup;
        this.movieBuffLookup = movieBuffLookup;
        this.viralSeedLookup = viralSeedLookup;
        this.tacticsQueryService = tacticsQueryService;
        this.nonObviousLeverLookup = nonObviousLeverLookup;
        this.playbookLookup = playbookLookup;
        this.spreaderSnapshotRepository = spreaderSnapshotRepository;
        this.viralSeedSnapshotRepository = viralSeedSnapshotRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public List<RecommendedActionCandidate> buildCandidateActions(Long entityId) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));

        String genre = resolveGenre(entity);
        boolean hasLanguage = entity.getLanguage() != null && !entity.getLanguage().isBlank();

        List<RecommendedActionCandidate> candidates = new ArrayList<>();
        addIfPresent(candidates, trailerTeaserTimingCandidate(entity));
        addIfPresent(candidates, firstSingleTimingCandidate(entity));
        addIfPresent(candidates, holidayWindowCandidate(entity, genre));
        addIfPresent(candidates, lowOnlinePresenceCandidate(entity));

        List<RecommendedActionCandidate> commonTacticCandidates = List.of();
        if (genre != null && hasLanguage) {
            addIfPresent(candidates, releaseDayCandidate(entity, genre));
            // No budget gate here (unlike the pre-loosening version of this method): a movie with no
            // budget on file - the common case for the small/independent productions this platform
            // tracks, see comparableBudgetCandidates - still gets genre+language comps, just not
            // narrowed to a budget tier.
            candidates.addAll(comparableBudgetCandidates(entity, genre));
            PeerTacticCandidates peerTactics = peerMarketingTacticCandidates(entity, genre);
            candidates.addAll(peerTactics.rare());
            commonTacticCandidates = peerTactics.common();
        }
        addIfPresent(candidates, genreAudienceReachCandidate(genre));

        List<String> keywords = extractKeywords(entity);
        addIfPresent(candidates, evangelistMobilizationCandidate(entity, keywords));
        addIfPresent(candidates, movieBuffOutreachCandidate(keywords));
        addIfPresent(candidates, viralSeedCandidate(keywords));
        addIfPresent(candidates, peakEngagementHoursCandidate(entity));
        addIfPresent(candidates, organicWordOfMouthCandidate(entity));
        candidates.addAll(generateNonObviousLeverCandidates(entity));
        candidates.addAll(generatePlaybookCandidates(entity));
        candidates.addAll(topSpreaderGapCandidates(entity));
        addIfPresent(candidates, viralSeedViewCountGapCandidate(entity));

        // Common tactics are withheld above (see COMMON_TACTIC_PREVALENCE_THRESHOLD) - only worth
        // adding back as a fallback once every other generator has had its say, when this movie's
        // plan is otherwise too thin and it hasn't released yet, and even then only for the ones
        // tracked posts don't already show as done.
        if (!commonTacticCandidates.isEmpty() && candidates.size() < COMMON_TACTIC_FILLER_MIN_ACTIONS
                && isNotYetReleased(entity)) {
            for (RecommendedActionCandidate candidate : commonTacticCandidates) {
                if (!alreadyPostedTacticSignal(entity.getId(), candidate.factorName())) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    // "Not yet released" mirrors RecommendedActionsService.todayOffsetFromRelease's sign convention
    // (daysToRelease > 0 means released). An entity with no releaseDate on file is treated as not yet
    // released rather than excluded - there's no evidence it has released.
    private boolean isNotYetReleased(ManagedEntity entity) {
        LocalDate releaseDate = entity.getReleaseDate();
        if (releaseDate == null) {
            return true;
        }
        long daysToRelease = ChronoUnit.DAYS.between(releaseDate, LocalDate.now(clock));
        return daysToRelease <= 0;
    }

    /** Tracked keyword strings for this entity, or empty if it has none - the gate shared by every
     *  candidate below that needs a keyword to query AuraMath with. */
    private static List<String> extractKeywords(ManagedEntity entity) {
        List<String> keywords = new ArrayList<>();
        if (entity.getKeywords() != null) {
            for (EntityKeyword ek : entity.getKeywords()) {
                if (ek != null && ek.getKeyword() != null && !ek.getKeyword().isBlank()) {
                    keywords.add(ek.getKeyword());
                }
            }
        }
        return keywords;
    }

    private static void addIfPresent(List<RecommendedActionCandidate> list, RecommendedActionCandidate candidate) {
        if (candidate != null) {
            list.add(candidate);
        }
    }

    // ==================== Factor 46 / 47 - server-computed calendar math ====================

    private RecommendedActionCandidate trailerTeaserTimingCandidate(ManagedEntity entity) {
        if (entity.getReleaseDate() == null || !isNotYetReleased(entity)) {
            return null;
        }
        int start = -OPTIMAL_MAX_DAYS_46;
        int end = -OPTIMAL_MIN_DAYS_46;
        List<String> facts = List.of(
                String.format(Locale.ROOT,
                        "This platform's box-office timing model calibrates a %d-%d day pre-release trailer/teaser " +
                                "window as a %s impact bonus.",
                        OPTIMAL_MIN_DAYS_46, OPTIMAL_MAX_DAYS_46, pctLabel(OPTIMAL_BONUS_46)),
                String.format(Locale.ROOT,
                        "Releasing a trailer/teaser fewer than %d days before release is calibrated as too late for " +
                                "that hype window (%s).",
                        SHORT_WINDOW_DAYS, pctLabel(SHORT_WINDOW_PENALTY)));
        return factorCandidate(FACTOR_TEASER_TRAILER, "trailer-teaser-timing",
                SERVER_COMPUTED_CONFIDENCE, start, end, facts);
    }

    private RecommendedActionCandidate firstSingleTimingCandidate(ManagedEntity entity) {
        if (entity.getReleaseDate() == null || !isNotYetReleased(entity)) {
            return null;
        }
        int start = -OPTIMAL_MAX_DAYS_47;
        int end = -OPTIMAL_MIN_DAYS_47;
        List<String> facts = List.of(String.format(Locale.ROOT,
                "This platform's box-office timing model calibrates a %d-%d day (6-8 week) pre-release " +
                        "first-single window as a %s impact bonus.",
                OPTIMAL_MIN_DAYS_47, OPTIMAL_MAX_DAYS_47, pctLabel(OPTIMAL_BONUS_47)));
        return factorCandidate(FACTOR_FIRST_SINGLE, "first-single-timing",
                SERVER_COMPUTED_CONFIDENCE, start, end, facts);
    }

    private static String pctLabel(double fraction) {
        return String.format(Locale.ROOT, "%+.0f%%", fraction * 100);
    }

    // ==================== Factor 61 - holiday proximity (calendar math) ====================

    // Genre-specific ideal release window that takes priority over the generic HOLIDAYS scan below
    // when it applies - a Romance release landing numerically close to Republic Day would be
    // calendar-correct but genre-wrong; Romance belongs near Valentine's Day instead. Extend with more
    // genre -> window mappings as they're identified.
    private static final List<Holiday> ROMANCE_IDEAL_WINDOW = List.of(
            new Holiday("Valentine's Day", LocalDate.of(2024, 2, 14)),
            new Holiday("Valentine's Day", LocalDate.of(2025, 2, 14)),
            new Holiday("Valentine's Day", LocalDate.of(2026, 2, 14)),
            new Holiday("Valentine's Day", LocalDate.of(2027, 2, 14))
    );

    private static boolean isRomanceGenre(String genre) {
        if (genre == null) {
            return false;
        }
        for (String token : genre.split(",")) {
            if (token.trim().toLowerCase(Locale.ROOT).startsWith("roman")) {
                return true;
            }
        }
        return false;
    }

    private RecommendedActionCandidate holidayWindowCandidate(ManagedEntity entity, String genre) {
        LocalDate releaseDate = entity.getReleaseDate();
        if (releaseDate == null || !isNotYetReleased(entity)) {
            return null;
        }
        List<Holiday> candidateHolidays = isRomanceGenre(genre) ? ROMANCE_IDEAL_WINDOW : HOLIDAYS;
        Holiday nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (Holiday holiday : candidateHolidays) {
            long distance = Math.abs(ChronoUnit.DAYS.between(releaseDate, holiday.date()));
            if (distance <= HOLIDAY_PROXIMITY_DAYS && distance < nearestDistance) {
                nearest = holiday;
                nearestDistance = distance;
            }
        }
        if (nearest == null) {
            return null;
        }
        BoxOfficeFactorCatalog.FactorDefinition def = BoxOfficeFactorCatalog.byNumber(FACTOR_HOLIDAY_RELEASE_WINDOWS);
        String fact = String.format(Locale.ROOT,
                "Release date is %d day(s) from %s (%s); this platform's factor model calibrates holiday-window " +
                        "releases at a %s to %s box-office impact.",
                nearestDistance, nearest.name(), nearest.date(),
                pctLabel(def.low()), pctLabel(def.high()));
        return new RecommendedActionCandidate(
                "factor-" + FACTOR_HOLIDAY_RELEASE_WINDOWS + "-holiday-proximity",
                def.name(), categorize(def), SERVER_COMPUTED_CONFIDENCE, 0, 0,
                buildWindowLabel(0, 0), List.of(fact), List.of(), List.of());
    }

    // ==================== Factor 61 - best release day-of-week (movies_data_collection) ====================

    private RecommendedActionCandidate releaseDayCandidate(ManagedEntity entity, String genre) {
        if (entity.getReleaseDate() == null || !isNotYetReleased(entity)) {
            return null;
        }
        List<Object[]> rows = moviesDataQueryService.findReleaseDayOfWeekStats(genre, entity.getLanguage());

        // The best-performing day of week by average revenue, among buckets with a confidence-worthy
        // sample - not just whichever day this movie already happens to be scheduled on, so this
        // candidate can actually recommend a day to marketing rather than merely describe one.
        Object[] best = null;
        Integer bestConfidence = null;
        for (Object[] row : rows) {
            if (row[2] == null) {
                continue;
            }
            long sampleCount = ((Number) row[1]).longValue();
            Integer confidence = compsConfidence(sampleCount);
            if (confidence == null) {
                continue;
            }
            double avgRevenue = ((Number) row[2]).doubleValue();
            if (best == null || avgRevenue > ((Number) best[2]).doubleValue()) {
                best = row;
                bestConfidence = confidence;
            }
        }
        if (best == null) {
            return null;
        }

        DayOfWeek bestDayOfWeek = fromPostgresDayOfWeek(((Number) best[0]).intValue());
        DayOfWeek actualDayOfWeek = entity.getReleaseDate().getDayOfWeek();
        long sampleCount = ((Number) best[1]).longValue();
        double avgRevenue = ((Number) best[2]).doubleValue();
        @SuppressWarnings("unchecked")
        List<String> exampleTitles = best.length > 3 && best[3] != null ? (List<String>) best[3] : List.of();
        String titleSuffix = exampleTitles.isEmpty() ? "" : " (e.g. " + String.join(", ", exampleTitles) + ")";

        String fact = bestDayOfWeek == actualDayOfWeek
                ? String.format(Locale.ROOT,
                        "Your scheduled release day (%s) already matches the best-performing release day for %d " +
                                "comparable %s %s releases%s, which averaged $%,.0f in revenue.",
                        capitalize(bestDayOfWeek), sampleCount, entity.getLanguage(), genre, titleSuffix, avgRevenue)
                : String.format(Locale.ROOT,
                        "Consider releasing on a %s instead of a %s: %d comparable %s %s releases on a %s%s " +
                                "averaged $%,.0f in revenue.",
                        capitalize(bestDayOfWeek), capitalize(actualDayOfWeek), sampleCount, entity.getLanguage(),
                        genre, capitalize(bestDayOfWeek), titleSuffix, avgRevenue);

        return new RecommendedActionCandidate(
                "factor-" + FACTOR_HOLIDAY_RELEASE_WINDOWS + "-release-day",
                "Best Release Day of Week",
                categorize(BoxOfficeFactorCatalog.byNumber(FACTOR_HOLIDAY_RELEASE_WINDOWS)),
                bestConfidence, 0, 0, buildWindowLabel(0, 0), List.of(fact), List.of(), List.of());
    }

    private static DayOfWeek fromPostgresDayOfWeek(int postgresDow) {
        return postgresDow == 0 ? DayOfWeek.SUNDAY : DayOfWeek.of(postgresDow);
    }

    private static String capitalize(DayOfWeek dayOfWeek) {
        String name = dayOfWeek.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    // ==================== Factor 52 - low/no online presence (absence of data as the signal) ====================

    // The other engagement-driven generators below (evangelistMobilizationCandidate,
    // peakEngagementHoursCandidate, organicWordOfMouthCandidate) all correctly stay silent when
    // there's too little mention data to measure anything from. But for a movie with near-zero
    // tracked online presence, that absence is exactly the thing a marketing team needs surfaced -
    // not "nothing to report" but "go build some visibility." This is the one generator where the
    // absence of engagement data is itself the grounding fact, not a reason to produce nothing.
    private RecommendedActionCandidate lowOnlinePresenceCandidate(ManagedEntity entity) {
        long totalMentions = mentionRepository.countByManagedEntityId(entity.getId());
        if (totalMentions >= LOW_PRESENCE_MENTION_THRESHOLD) {
            return null;
        }
        String fact = String.format(Locale.ROOT,
                "Only %d mention(s) of this movie have been tracked online to date, below the %d-mention floor " +
                        "this platform uses to consider organic buzz underway - too little online presence to " +
                        "wait for it to build on its own.",
                totalMentions, LOW_PRESENCE_MENTION_THRESHOLD);
        return factorCandidate(FACTOR_MICRO_VIDEO_CAMPAIGNS, "low-online-presence", LOW_PRESENCE_CONFIDENCE,
                LOW_PRESENCE_WINDOW_START_DAYS, LOW_PRESENCE_WINDOW_END_DAYS, List.of(fact));
    }

    // ==================== Factor 87 / 88 - genre+language(+budget) comps (movies_data_collection) ====================

    // Budget-scoped when the entity has a real budget on file; otherwise falls back to genre+language
    // comps across every budget tier rather than skipping this candidate entirely - a small/independent
    // production with no budget recorded is exactly the case this platform's actual data skews toward
    // (most tracked movies have no budget figure), and it still deserves comps-backed screen-count/P&A
    // guidance, just not narrowed to a budget range it can't be compared against.
    private List<RecommendedActionCandidate> comparableBudgetCandidates(ManagedEntity entity, String genre) {
        Double budget = entity.getBudget();
        boolean budgetScoped = budget != null && budget > 0;
        double minBudget = budgetScoped ? budget * (1 - BUDGET_RANGE_FRACTION) : 0;
        double maxBudget = budgetScoped ? budget * (1 + BUDGET_RANGE_FRACTION) : Double.MAX_VALUE;

        List<Object[]> rows = moviesDataQueryService.findGenreLanguageBudgetComps(genre, entity.getLanguage(), minBudget, maxBudget);
        if (rows.isEmpty() || rows.get(0)[0] == null || rows.get(0)[1] == null) {
            return List.of();
        }
        Object[] row = rows.get(0);
        long sampleCount = ((Number) row[0]).longValue();
        Integer confidence = compsConfidence(sampleCount);
        if (confidence == null) {
            return List.of();
        }
        double avgRevenue = ((Number) row[1]).doubleValue();

        String fact = budgetScoped
                ? String.format(Locale.ROOT,
                        "%d comparable %s %s releases (budget within +/-50%%) averaged $%,.0f in revenue.",
                        sampleCount, entity.getLanguage(), genre, avgRevenue)
                : String.format(Locale.ROOT,
                        "%d comparable %s %s releases (no budget on file for this movie, so shown across all " +
                                "budgets) averaged $%,.0f in revenue.",
                        sampleCount, entity.getLanguage(), genre, avgRevenue);

        List<RecommendedActionCandidate> results = new ArrayList<>(2);
        results.add(factorCandidateFromWindowTable(FACTOR_SCREEN_COUNT, "screen-count-allocation", confidence, fact));
        results.add(factorCandidateFromWindowTable(FACTOR_PA_COMMITMENTS, "pa-commitments", confidence, fact));
        return results;
    }

    static Integer compsConfidence(long sampleCount) {
        if (sampleCount < COMPS_TIER_MIN_SAMPLE) {
            return null;
        }
        if (sampleCount >= COMPS_TIER_HIGH_SAMPLE) {
            return COMPS_CONFIDENCE_HIGH;
        }
        if (sampleCount >= COMPS_TIER_MID_SAMPLE) {
            return COMPS_CONFIDENCE_MID;
        }
        return COMPS_CONFIDENCE_LOW;
    }

    // ==================== Peer marketing-tactic precedent (movie_marketing_tactics) ====================

    // Not grounded in any BoxOfficeFactorCatalog factor, unlike every other generator in this file -
    // movie_marketing_tactics records real tactics other movies ran, not a calibrated impact range, so
    // there's no factor number/def to derive factorName/category from here. category is fixed rather
    // than tiered off a midpoint that doesn't exist for this data source; confidencePct is still
    // tiered (see PEER_TACTIC_TIER_* above), off the real distinct-comp-movie count instead.
    private record PeerTacticCandidates(List<RecommendedActionCandidate> rare, List<RecommendedActionCandidate> common) {
    }

    // Splits peer-tactic candidates into "rare" (always surfaced - real, uncommon precedent the
    // marketing team likely hasn't considered) and "common" (withheld by default - see
    // COMMON_TACTIC_PREVALENCE_THRESHOLD) buckets, off the same peer rows already fetched for the
    // genre+language comparable pool. Prevalence is measured against that same pool
    // (totalDistinctPeerMovies), not a global count, so "almost every movie" means "almost every
    // comparable movie" - consistent with every other peer-comps candidate in this file.
    private PeerTacticCandidates peerMarketingTacticCandidates(ManagedEntity entity, String genre) {
        List<Object[]> rows = tacticsQueryService.findPeerTactics(genre, entity.getLanguage());
        if (rows.isEmpty()) {
            return new PeerTacticCandidates(List.of(), List.of());
        }

        Map<String, List<Object[]>> byClassification = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String key = row[2] + "|" + row[3];
            byClassification.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        long totalDistinctPeerMovies = rows.stream().map(r -> (String) r[0]).distinct().count();

        List<RecommendedActionCandidate> rare = new ArrayList<>();
        List<RecommendedActionCandidate> common = new ArrayList<>();
        for (List<Object[]> bucketRows : byClassification.values()) {
            RecommendedActionCandidate candidate = peerMarketingTacticCandidate(genre, bucketRows);
            if (candidate == null) {
                continue;
            }
            long distinctMovies = bucketRows.stream().map(r -> (String) r[0]).distinct().count();
            if (isCommonTactic(distinctMovies, totalDistinctPeerMovies)) {
                common.add(candidate);
            } else {
                rare.add(candidate);
            }
        }
        return new PeerTacticCandidates(rare, common);
    }

    static boolean isCommonTactic(long distinctMovies, long totalDistinctPeerMovies) {
        return totalDistinctPeerMovies >= COMMON_TACTIC_MIN_PEER_SAMPLE
                && (double) distinctMovies / totalDistinctPeerMovies >= COMMON_TACTIC_PREVALENCE_THRESHOLD;
    }

    // Extracts search-worthy tokens from a common tactic's classification name (e.g. "Teaser
    // Trailers" -> {"teaser", "trailers"}) for matching against tracked post content - see
    // alreadyPostedTacticSignal. Generic marketing vocabulary and short tokens are stripped so the
    // search doesn't false-positive on unrelated posts.
    static Set<String> tacticSignalKeywords(String classificationText) {
        if (classificationText == null) {
            return Set.of();
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : classificationText.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (token.length() >= 4 && !TACTIC_KEYWORD_STOPWORDS.contains(token)) {
                keywords.add(token);
            }
        }
        return keywords;
    }

    // Whether tracked posts for this entity already show signs of this common tactic having
    // happened (e.g. a "teaser" mention once the classification is "Teaser Trailers") - checked
    // before adding a common-tactic candidate as a low-inventory fallback, so this platform never
    // recommends releasing something posts already show has been released. No keywords extracted
    // (e.g. an all-stopword classification name) means no evidence either way, so the candidate is
    // not held back.
    private boolean alreadyPostedTacticSignal(Long entityId, String classificationText) {
        for (String keyword : tacticSignalKeywords(classificationText)) {
            if (mentionRepository.existsByManagedEntityIdAndContentContainingIgnoreCase(entityId, keyword)) {
                return true;
            }
        }
        return false;
    }

    private RecommendedActionCandidate peerMarketingTacticCandidate(String genre, List<Object[]> rows) {
        // Newest comps first - the most recent real precedent is the most relevant one to lead with
        // and to keep when capping at TACTIC_EXAMPLES_PER_CANDIDATE_LIMIT.
        List<Object[]> sorted = rows.stream()
                .sorted(Comparator.comparing((Object[] r) -> (String) r[1]).reversed())
                .toList();

        Object[] first = sorted.get(0);
        String mainClassification = (String) first[2];
        String subClassification = (String) first[3];

        long distinctMovies = sorted.stream().map(r -> (String) r[0]).distinct().count();
        if (distinctMovies < PEER_TACTIC_TIER_MIN) {
            return null;
        }

        List<String> facts = new ArrayList<>();
        facts.add(String.format(Locale.ROOT,
                "%d comparable %s movie(s) used a %s tactic in the %s category.",
                distinctMovies, genre, subClassification, mainClassification));
        sorted.stream()
                .limit(TACTIC_EXAMPLES_PER_CANDIDATE_LIMIT)
                .forEach(r -> facts.add(String.format(Locale.ROOT,
                        "%s (%s) used a comparable %s tactic: \"%s\"",
                        r[0], r[1], subClassification, r[4])));

        int confidence = peerTacticConfidence(distinctMovies);
        return new RecommendedActionCandidate(
                "peer-tactic-" + slug(mainClassification) + "-" + slug(subClassification),
                subClassification, RecommendedActionCategory.MEDIUM_IMPACT, confidence,
                PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS,
                buildWindowLabel(PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS),
                facts, List.of(), List.of());
    }

    static int peerTacticConfidence(long distinctMovieCount) {
        if (distinctMovieCount >= PEER_TACTIC_TIER_HIGH) {
            return PEER_TACTIC_CONFIDENCE_HIGH;
        }
        if (distinctMovieCount >= PEER_TACTIC_TIER_MID) {
            return PEER_TACTIC_CONFIDENCE_MID;
        }
        return PEER_TACTIC_CONFIDENCE_LOW;
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    // ==================== Factor 52 - genre audience reach (AuraMath, no budget required) ====================

    // The only candidate grounded in a live external call rather than this platform's own DB - AuraMath's
    // genre-scoped audience-reach data (see GenreMarketingLookupService) needs nothing but a genre, so
    // it's reachable for a movie with no budget and no tracked mentions yet, unlike every other
    // comps/engagement-driven generator in this file.
    private RecommendedActionCandidate genreAudienceReachCandidate(String genre) {
        String primaryGenre = primaryGenreToken(genre);
        if (primaryGenre == null) {
            return null;
        }
        GenreMarketingLookupService.GenreReach reach = genreMarketingLookup.getGenreReach(primaryGenre);
        if (reach == null) {
            return null;
        }

        List<String> facts = new ArrayList<>();
        if (reach.totalViewers() != null) {
            facts.add(String.format(Locale.ROOT,
                    "This platform tracks %,d potential viewers with an affinity for %s content.",
                    reach.totalViewers(), primaryGenre));
        }
        if (reach.topChannel() != null) {
            facts.add(String.format(Locale.ROOT,
                    "This platform's channel-strategy model recommends %s as the top channel to reach %s audiences.",
                    reach.topChannel(), primaryGenre));
        }
        if (facts.isEmpty()) {
            return null;
        }
        return factorCandidateFromWindowTable(
                FACTOR_MICRO_VIDEO_CAMPAIGNS, "genre-audience-reach", GENRE_REACH_CONFIDENCE, facts);
    }

    /** First token of a comma-separated multi-genre string - AuraMath's genre endpoints take one genre. */
    private static String primaryGenreToken(String genre) {
        if (genre == null || genre.isBlank()) {
            return null;
        }
        String first = genre.split(",")[0].trim();
        return first.isEmpty() ? null : first;
    }

    // ==================== Factor 17 - evangelist / core fanbase mobilization ====================

    private RecommendedActionCandidate evangelistMobilizationCandidate(ManagedEntity entity, List<String> keywords) {
        if (keywords.isEmpty()) {
            return null;
        }

        Map<String, SpreaderProfile> spreaders = fetchSpreaderProfiles(keywords);
        if (spreaders.isEmpty()) {
            return null;
        }

        Map<String, Long> positiveCounts = filterPredominantlyPositive(entity.getId(), spreaders.keySet());
        if (positiveCounts.size() < EVANGELIST_TIER_MIN) {
            return null;
        }

        // Ranked by AuraMath's total_views (summed views on the author's matching posts) - the only
        // real reach proxy the top-50-spreaders endpoint provides. influenceTier/primaryPlatform are
        // not part of that endpoint's response and are always null; do not sort/filter on them here.
        Comparator<SpreaderProfile> byViewsThenPositiveCount = Comparator
                .comparingLong(SpreaderProfile::totalViews).reversed()
                .thenComparing(p -> positiveCounts.getOrDefault(p.globalUserId(), 0L), Comparator.reverseOrder());
        List<SpreaderProfile> rankedQualifying = spreaders.values().stream()
                .filter(p -> positiveCounts.containsKey(p.globalUserId()))
                .sorted(byViewsThenPositiveCount)
                .toList();
        List<String> topHandles = rankedQualifying.stream()
                .map(SpreaderProfile::globalUserId)
                .limit(TOP_HANDLES_LIMIT)
                .toList();
        // relevantUsers is the fuller "View Details" roster (up to MAX_RELEVANT_USERS) behind
        // topHandles' short inline-text sample - same ranking, richer per-account data (platform,
        // profile link) for the marketing team to page through and act on directly.
        List<RecommendedActionUser> relevantUsers = rankedQualifying.stream()
                .limit(MAX_RELEVANT_USERS)
                .map(p -> new RecommendedActionUser(p.globalUserId(), p.primaryPlatform(), p.profileUrl()))
                .toList();

        List<String> facts = new ArrayList<>();
        facts.add(String.format(Locale.ROOT,
                "%d positive-sentiment accounts identified across %d tracked keyword(s) (predominantly positive " +
                        "toward this movie: positive mentions outnumber negative and are at least as many as neutral).",
                positiveCounts.size(), keywords.size()));
        if (!topHandles.isEmpty()) {
            facts.add("Top positive-sentiment account(s) to mobilize (ranked by reach): "
                    + String.join(", ", topHandles) + ".");
        }
        String liftFact = allyMobilizationLiftFact(entity);
        if (liftFact != null) {
            facts.add(liftFact);
        }

        int confidence = evangelistConfidence(positiveCounts.size());
        return factorCandidateFromWindowTable(
                FACTOR_FANBASE_MOBILIZATION, "evangelist-mobilization", confidence, facts, topHandles,
                relevantUsers);
    }

    private Map<String, SpreaderProfile> fetchSpreaderProfiles(List<String> keywords) {
        Map<String, SpreaderProfile> deduped = new LinkedHashMap<>();
        for (String keyword : keywords) {
            for (SpreaderProfile profile : spreaderLookup.getSpreaderProfiles(keyword)) {
                if (profile.globalUserId() == null || profile.globalUserId().isBlank()) {
                    continue;
                }
                deduped.putIfAbsent(profile.globalUserId(), profile);
            }
        }
        return deduped;
    }

    // Mirrors MobilizeAlliesService.filterPredominantlyPositive's exact pos>neg && pos>=neu threshold
    // - keep the two in sync.
    private Map<String, Long> filterPredominantlyPositive(Long entityId, Set<String> authors) {
        if (authors.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = mentionRepository.countSentimentByAuthorsForEntity(entityId, authors);
        Map<String, EnumMap<Sentiment, Long>> byAuthor = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String author = (String) row[0];
            Sentiment sentiment = (Sentiment) row[1];
            long count = ((Number) row[2]).longValue();
            byAuthor.computeIfAbsent(author, k -> new EnumMap<>(Sentiment.class)).merge(sentiment, count, Long::sum);
        }
        Map<String, Long> positive = new LinkedHashMap<>();
        for (Map.Entry<String, EnumMap<Sentiment, Long>> entry : byAuthor.entrySet()) {
            EnumMap<Sentiment, Long> counts = entry.getValue();
            long pos = counts.getOrDefault(Sentiment.POSITIVE, 0L);
            long neg = counts.getOrDefault(Sentiment.NEGATIVE, 0L);
            long neu = counts.getOrDefault(Sentiment.NEUTRAL, 0L);
            if (pos > 0 && pos > neg && pos >= neu) {
                positive.put(entry.getKey(), pos);
            }
        }
        return positive;
    }

    // Used by movieBuffOutreachCandidate below - that AuraMath endpoint (unlike
    // top-50-spreaders) does return a real influenceTier value.
    static int tierRank(String tier) {
        if (tier == null) {
            return Integer.MAX_VALUE;
        }
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "TIER_1", "TIER1", "T1" -> 1;
            case "TIER_2", "TIER2", "T2" -> 2;
            case "TIER_3", "TIER3", "T3" -> 3;
            case "TIER_4", "TIER4", "T4" -> 4;
            default -> Integer.MAX_VALUE - 1;
        };
    }

    static int evangelistConfidence(long qualifyingCount) {
        if (qualifyingCount >= EVANGELIST_TIER_HIGH) {
            return EVANGELIST_CONFIDENCE_HIGH;
        }
        if (qualifyingCount >= EVANGELIST_TIER_MID) {
            return EVANGELIST_CONFIDENCE_MID;
        }
        return EVANGELIST_CONFIDENCE_LOW;
    }

    // Historical mention-volume lift correlated with past MobilizeAction events on comparable
    // (same budget tier + language + genre) entities. Returns null (omit the fact, not the whole
    // candidate) when there isn't enough comparable history.
    private String allyMobilizationLiftFact(ManagedEntity entity) {
        if (entity.getBudget() == null || entity.getBudget() <= 0
                || entity.getLanguage() == null || entity.getLanguage().isBlank()
                || entity.getGenre() == null || entity.getGenre().isBlank()) {
            return null;
        }
        double minBudget = entity.getBudget() * (1 - BUDGET_RANGE_FRACTION);
        double maxBudget = entity.getBudget() * (1 + BUDGET_RANGE_FRACTION);
        List<ManagedEntity> comparable = entityRepository.findByTypeAndBudgetBetweenAndIdNot(
                MOVIE_TYPE, minBudget, maxBudget, entity.getId());
        List<Long> comparableIds = comparable.stream()
                .filter(m -> entity.getLanguage().equalsIgnoreCase(m.getLanguage()))
                .filter(m -> entity.getGenre().equalsIgnoreCase(m.getGenre()))
                .map(ManagedEntity::getId)
                .toList();
        if (comparableIds.isEmpty()) {
            return null;
        }

        List<Double> liftRatios = new ArrayList<>();
        for (MobilizeAction event : mobilizeActionRepository.findByEntityIdIn(comparableIds)) {
            Instant eventTime = event.getCreatedAt();
            Instant beforeStart = eventTime.minus(ALLY_LIFT_WINDOW_DAYS, ChronoUnit.DAYS);
            Instant afterEnd = eventTime.plus(ALLY_LIFT_WINDOW_DAYS, ChronoUnit.DAYS);
            long beforeCount = mentionRepository.countByManagedEntityIdAndPostDateBetween(
                    event.getEntityId(), beforeStart, eventTime);
            long afterCount = mentionRepository.countByManagedEntityIdAndPostDateBetween(
                    event.getEntityId(), eventTime, afterEnd);
            if (beforeCount > 0) {
                liftRatios.add((double) afterCount / beforeCount);
            }
        }
        if (liftRatios.size() < MIN_MOBILIZE_EVENTS_FOR_LIFT) {
            return null;
        }
        double avgLift = liftRatios.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return String.format(Locale.ROOT,
                "Ally mobilization events of similar scale correlated with a %.1fx mention-volume lift across %d " +
                        "comparable historical events.",
                avgLift, liftRatios.size());
    }

    // ==================== Factor 53 - movie-buff outreach (AuraMath) ====================

    // Distinct data source from evangelistMobilizationCandidate above: that one measures THIS
    // platform's own mention sentiment for authors AuraMath ranks as general top spreaders (Hawkes
    // influence). This one asks AuraMath directly which of those authors it has already classified
    // as "Movie Buff" (positive tone, high branching ratio) independent of any one keyword -
    // a real, tiered count answering "how many movie buffs should this movie approach," grounded in
    // AuraMath's own classification rather than a percentage this service would otherwise have to
    // invent.
    private RecommendedActionCandidate movieBuffOutreachCandidate(List<String> keywords) {
        if (keywords.isEmpty()) {
            return null;
        }
        Map<String, MovieBuffLookupService.MovieBuff> buffByAuthor = new LinkedHashMap<>();
        for (String keyword : keywords) {
            for (MovieBuffLookupService.MovieBuff buff : movieBuffLookup.getMovieBuffs(keyword)) {
                buffByAuthor.putIfAbsent(buff.author(), buff);
            }
        }
        if (buffByAuthor.isEmpty()) {
            return null;
        }

        long tier1Or2Count = buffByAuthor.values().stream()
                .filter(b -> tierRank(b.influenceTier()) <= 2).count();

        List<MovieBuffLookupService.MovieBuff> ranked = buffByAuthor.values().stream()
                .sorted(Comparator.comparingInt(b -> tierRank(b.influenceTier())))
                .toList();
        List<String> topHandles = ranked.stream()
                .map(MovieBuffLookupService.MovieBuff::author)
                .limit(TOP_HANDLES_LIMIT)
                .toList();
        // relevantUsers is the fuller "View Details" roster (up to MAX_RELEVANT_USERS) behind
        // topHandles' short inline-text sample - same tier ranking, richer per-account data (platform,
        // profile link) for the marketing team to page through and act on directly.
        List<RecommendedActionUser> relevantUsers = ranked.stream()
                .limit(MAX_RELEVANT_USERS)
                .map(b -> new RecommendedActionUser(b.author(), b.platform(), b.profileUrl()))
                .toList();

        List<String> facts = new ArrayList<>();
        facts.add(String.format(Locale.ROOT,
                "This platform has identified %d movie buff(s) (positive-tone, high-branching-ratio accounts) " +
                        "across %d tracked keyword(s) for this movie.",
                buffByAuthor.size(), keywords.size()));
        if (tier1Or2Count > 0) {
            facts.add(String.format(Locale.ROOT,
                    "%d of these are Tier-1/2 influence accounts - approach these first for the highest expected " +
                            "visibility lift per outreach.",
                    tier1Or2Count));
        }
        if (!topHandles.isEmpty()) {
            facts.add("Top movie-buff account(s) to approach first: " + String.join(", ", topHandles) + ".");
        }
        return factorCandidateFromWindowTable(
                FACTOR_INFLUENCER_PROMOTIONS, "movie-buff-outreach", MOVIE_BUFF_CONFIDENCE, facts,
                topHandles, relevantUsers);
    }

    // ==================== Factor 53 - viral seed outreach (AuraMath) ====================

    // Different targeting strategy from movie-buff outreach above: viral seeds are the accounts
    // AuraMath's composite infectivity/reach/influence score says are best to strategically seed NEW
    // promotional content with (a trailer drop, a teaser), regardless of whether they've shown any
    // prior sentiment toward this movie - not "who already likes us" but "who can make new content
    // spread."
    private RecommendedActionCandidate viralSeedCandidate(List<String> keywords) {
        if (keywords.isEmpty()) {
            return null;
        }
        Map<String, ViralSeedLookupService.ViralSeed> seedByAuthor = new LinkedHashMap<>();
        for (String keyword : keywords) {
            for (ViralSeedLookupService.ViralSeed seed : viralSeedLookup.getViralSeeds(keyword)) {
                seedByAuthor.putIfAbsent(seed.author(), seed);
            }
        }
        if (seedByAuthor.isEmpty()) {
            return null;
        }

        // AuraMath's own top-ranked ordering (LinkedHashMap preserves first-seen/insertion order).
        List<ViralSeedLookupService.ViralSeed> ranked = new ArrayList<>(seedByAuthor.values());
        List<String> topHandles = ranked.stream()
                .map(ViralSeedLookupService.ViralSeed::author)
                .limit(TOP_HANDLES_LIMIT)
                .toList();
        // relevantUsers is the fuller "View Details" roster (up to MAX_RELEVANT_USERS) behind
        // topHandles' short inline-text sample - same AuraMath ranking, richer per-account data
        // (platform, profile link) for the marketing team to page through and act on directly.
        List<RecommendedActionUser> relevantUsers = ranked.stream()
                .limit(MAX_RELEVANT_USERS)
                .map(s -> new RecommendedActionUser(s.author(), s.primaryPlatform(), s.profileUrl()))
                .toList();

        List<String> facts = new ArrayList<>();
        facts.add(String.format(Locale.ROOT,
                "This platform has identified %d viral-seed account(s) across %d tracked keyword(s) for this " +
                        "movie, ranked by a composite of infectivity, engagement, and reach.",
                seedByAuthor.size(), keywords.size()));
        String topPlatform = ranked.stream()
                .map(ViralSeedLookupService.ViralSeed::primaryPlatform)
                .filter(p -> p != null && !p.isBlank())
                .findFirst().orElse(null);
        if (topPlatform != null) {
            facts.add(String.format(Locale.ROOT,
                    "The top-ranked seed account's primary platform is %s - consider giving it early or exclusive " +
                            "access to teaser content.",
                    topPlatform));
        }
        if (!topHandles.isEmpty()) {
            facts.add("Top-ranked viral-seed account(s) to approach: " + String.join(", ", topHandles) + ".");
        }
        return factorCandidateFromWindowTable(
                FACTOR_INFLUENCER_PROMOTIONS, "viral-seed-outreach", VIRAL_SEED_CONFIDENCE, facts, topHandles,
                relevantUsers);
    }

    // ==================== Factor 52 - peak audience engagement hours ====================

    private RecommendedActionCandidate peakEngagementHoursCandidate(ManagedEntity entity) {
        HourlyActivityResponse response = dashboardService.getHourlyActivity(
                entity.getId(), TimePeriod.DAY30, null, null, null);
        long totalActiveUsers = response.getTotalActiveUsers();
        Integer confidence = hourlyConfidence(totalActiveUsers);
        if (confidence == null) {
            return null;
        }

        List<Integer> topHours = response.getHourlyDistribution().entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_PEAK_HOURS)
                .map(Map.Entry::getKey)
                .toList();
        if (topHours.isEmpty()) {
            return null;
        }

        String hoursLabel = topHours.stream()
                .map(h -> String.format(Locale.ROOT, "%02d:00", h))
                .collect(Collectors.joining(", "));
        String fact = String.format(Locale.ROOT,
                "Peak audience engagement hours over the last 30 days: %s (top %d of 24 hours by active users, " +
                        "%d total active users).",
                hoursLabel, topHours.size(), totalActiveUsers);
        return factorCandidateFromWindowTable(FACTOR_MICRO_VIDEO_CAMPAIGNS, "peak-engagement-hours", confidence, fact);
    }

    static Integer hourlyConfidence(long totalActiveUsers) {
        if (totalActiveUsers < HOURLY_TIER_MIN) {
            return null;
        }
        if (totalActiveUsers >= HOURLY_TIER_HIGH) {
            return HOURLY_CONFIDENCE_HIGH;
        }
        if (totalActiveUsers >= HOURLY_TIER_MID) {
            return HOURLY_CONFIDENCE_MID;
        }
        return HOURLY_CONFIDENCE_LOW;
    }

    // ==================== Factor 91 - post-day-1 word-of-mouth (real post-release mention data) ====================

    // Deliberately measures raw mention volume/sentiment, not a promotional-vs-organic-filtered
    // subset - no date-scoped promotional/organic breakdown query exists in this codebase (see
    // DashboardService.getPromotionalMix, which is all-time only), so the fact text below describes
    // exactly what was measured rather than implying a distinction that wasn't actually made.
    private RecommendedActionCandidate organicWordOfMouthCandidate(ManagedEntity entity) {
        LocalDate releaseDate = entity.getReleaseDate();
        if (releaseDate == null) {
            return null;
        }
        WindowSpec window = WINDOW_BY_FACTOR.get(FACTOR_ORGANIC_WORD_OF_MOUTH);
        Instant start = releaseDate.plusDays(window.startDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = releaseDate.plusDays(window.endDays() + 1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        long totalMentions = mentionRepository.countByManagedEntityIdAndPostDateBetween(entity.getId(), start, end);
        Integer confidence = wordOfMouthConfidence(totalMentions);
        if (confidence == null) {
            return null;
        }
        long positive = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entity.getId(), Sentiment.POSITIVE, start, end);
        long negative = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entity.getId(), Sentiment.NEGATIVE, start, end);
        double positivePct = (double) positive / totalMentions * 100.0;
        double negativePct = (double) negative / totalMentions * 100.0;

        String fact = String.format(Locale.ROOT,
                "%d mentions were tracked in the day-%d-to-day-%d post-release word-of-mouth window (%.1f%% positive, " +
                        "%.1f%% negative).",
                totalMentions, window.startDays(), window.endDays(), positivePct, negativePct);
        return factorCandidateFromWindowTable(FACTOR_ORGANIC_WORD_OF_MOUTH, "organic-word-of-mouth", confidence, fact);
    }

    static Integer wordOfMouthConfidence(long totalMentions) {
        if (totalMentions < WORD_OF_MOUTH_TIER_MIN) {
            return null;
        }
        if (totalMentions >= WORD_OF_MOUTH_TIER_HIGH) {
            return WORD_OF_MOUTH_CONFIDENCE_HIGH;
        }
        if (totalMentions >= WORD_OF_MOUTH_TIER_MID) {
            return WORD_OF_MOUTH_CONFIDENCE_MID;
        }
        return WORD_OF_MOUTH_CONFIDENCE_LOW;
    }

    // ==================== Non-obvious lever (AuraMath F5, pooled 'ALL' cohort) ====================

    // Not grounded in BoxOfficeFactorCatalog, like peerMarketingTacticCandidates above: AuraMath's F5
    // lever-miner findings have their own statistical evidence (p-value, FDR q-value, direction, sample
    // size), so category is fixed rather than tiered off a catalog midpoint that doesn't exist for this
    // data source. No prose supportingFacts here - the numbers ride along as statisticalEvidence, and
    // RecommendedActionsService's LLM pass phrases them into a sentence.
    private List<RecommendedActionCandidate> generateNonObviousLeverCandidates(ManagedEntity entity) {
        List<RecommendedActionCandidate> candidates = new ArrayList<>();
        for (NonObviousLeverLookupService.LeverFinding finding : nonObviousLeverLookup.getNonObviousLevers(entity.getId())) {
            if (finding.fdrQValue() >= STATISTICAL_EVIDENCE_Q_VALUE_BAR) {
                continue;
            }
            RecommendedActionCandidate.StatisticalEvidence evidence = new RecommendedActionCandidate.StatisticalEvidence(
                    finding.featureName(), finding.direction(), finding.pValue(), finding.fdrQValue(),
                    finding.nEntities(), null, null, null);
            candidates.add(new RecommendedActionCandidate(
                    "nonobvious-lever-" + slug(finding.featureName()),
                    finding.featureName(), RecommendedActionCategory.MEDIUM_IMPACT,
                    statisticalEvidenceConfidence(finding.fdrQValue()),
                    PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS,
                    buildWindowLabel(PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS),
                    List.of(), List.of(), List.of(), evidence));
        }
        return candidates;
    }

    // ==================== Playbook sequence (AuraMath F7, entity's (industry, language) cohort) ====================

    // Same "not grounded in BoxOfficeFactorCatalog, no prose supportingFacts" reasoning as
    // generateNonObviousLeverCandidates above. Skipped entirely (like every other genre/language-gated
    // generator in this file) when the entity has no industry or language on file - AuraMath's playbook
    // endpoint requires both to resolve a cohort. candidateId is suffixed with a running index past the
    // first qualifying pattern for this cohort, so two distinct mined sequences for the same cohort never
    // collide on the same id (playbook_patterns can hold more than one qualifying row per cohort, unlike
    // nonobvious_lever_findings' one-row-per-feature shape).
    private List<RecommendedActionCandidate> generatePlaybookCandidates(ManagedEntity entity) {
        String industry = entity.getIndustry();
        String language = entity.getLanguage();
        if (industry == null || industry.isBlank() || language == null || language.isBlank()) {
            return List.of();
        }

        String cohortSlug = slug(industry + "-" + language);
        List<RecommendedActionCandidate> candidates = new ArrayList<>();
        int qualifying = 0;
        for (PlaybookLookupService.PlaybookPattern pattern : playbookLookup.getPlaybookPatterns(industry, language)) {
            if (pattern.fdrQValue() >= STATISTICAL_EVIDENCE_Q_VALUE_BAR) {
                continue;
            }
            qualifying++;
            String candidateId = "playbook-sequence-" + cohortSlug + (qualifying == 1 ? "" : "-" + qualifying);
            RecommendedActionCandidate.StatisticalEvidence evidence = new RecommendedActionCandidate.StatisticalEvidence(
                    null, null, null, pattern.fdrQValue(), pattern.nEntities(),
                    pattern.patternSequence(), pattern.supportTopTier(), pattern.supportBottomTier());
            candidates.add(new RecommendedActionCandidate(
                    candidateId, industry + " / " + language + " playbook sequence",
                    RecommendedActionCategory.MEDIUM_IMPACT, statisticalEvidenceConfidence(pattern.fdrQValue()),
                    PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS,
                    buildWindowLabel(PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS),
                    List.of(), List.of(), List.of(), evidence));
        }
        return candidates;
    }

    static int statisticalEvidenceConfidence(double fdrQValue) {
        if (fdrQValue < STATISTICAL_EVIDENCE_Q_TIER_HIGH) {
            return STATISTICAL_EVIDENCE_CONFIDENCE_HIGH;
        }
        if (fdrQValue < STATISTICAL_EVIDENCE_Q_TIER_MID) {
            return STATISTICAL_EVIDENCE_CONFIDENCE_MID;
        }
        return STATISTICAL_EVIDENCE_CONFIDENCE_LOW;
    }

    // ==================== Top-spreader language-coverage gap (this platform's periodic AuraMath
    // top-spreaders sync, see TopSpreaderLanguageSyncService/EntityLanguageSpreaderSnapshot) ====================

    // One candidate per language this movie is actually being marketed in (i.e. has a stored
    // EntityLanguageSpreaderSnapshot, itself only populated for languages the entity has a tracked
    // keyword tagged with), comparing this movie's own top-spreader count in that language against the
    // best-covered budget-comparable movie's. No budget on file - null or the UNDISCLOSED_BUDGET_SENTINEL
    // production houses' non-disclosure gets recorded as - means there's no real budget to scope
    // comparable movies by, so this generator produces nothing for the movie at all rather than falling
    // back to an unscoped comparison like comparableBudgetCandidates does: "of similar budget" is the
    // entire premise of this candidate, not an optional narrowing.
    private List<RecommendedActionCandidate> topSpreaderGapCandidates(ManagedEntity entity) {
        if (!hasRealBudget(entity.getBudget())) {
            return List.of();
        }
        List<EntityLanguageSpreaderSnapshot> ownSnapshots = spreaderSnapshotRepository.findByEntityId(entity.getId());
        if (ownSnapshots.isEmpty()) {
            return List.of();
        }

        double minBudget = entity.getBudget() * (1 - BUDGET_RANGE_FRACTION);
        double maxBudget = entity.getBudget() * (1 + BUDGET_RANGE_FRACTION);
        List<Long> comparableIds = entityRepository
                .findByTypeAndBudgetBetweenAndIdNot(MOVIE_TYPE, minBudget, maxBudget, entity.getId()).stream()
                .filter(m -> hasRealBudget(m.getBudget()))
                .map(ManagedEntity::getId)
                .toList();
        if (comparableIds.isEmpty()) {
            return List.of();
        }

        List<RecommendedActionCandidate> candidates = new ArrayList<>();
        for (EntityLanguageSpreaderSnapshot ownSnapshot : ownSnapshots) {
            addIfPresent(candidates, topSpreaderGapCandidate(ownSnapshot, comparableIds));
        }
        return candidates;
    }

    // A budget value of null, non-positive, or the UNDISCLOSED_BUDGET_SENTINEL (404) all mean "no real
    // budget figure to compare against" - the 404 case is a production house declining to disclose the
    // budget for an underperforming movie, recorded as that literal number rather than left null.
    static boolean hasRealBudget(Double budget) {
        return budget != null && budget > 0 && budget != UNDISCLOSED_BUDGET_SENTINEL;
    }

    private RecommendedActionCandidate topSpreaderGapCandidate(EntityLanguageSpreaderSnapshot ownSnapshot,
                                                                  List<Long> comparableIds) {
        String language = ownSnapshot.getLanguage();
        List<EntityLanguageSpreaderSnapshot> compSnapshots = spreaderSnapshotRepository
                .findByEntityIdInAndLanguageIgnoreCase(comparableIds, language);
        if (compSnapshots.isEmpty()) {
            return null;
        }

        // The single best-covered comparable movie for this language - a concrete, named precedent is
        // more actionable than an averaged statistic, same reasoning as peerMarketingTacticCandidate's
        // real quoted example over an aggregate.
        EntityLanguageSpreaderSnapshot best = compSnapshots.stream()
                .max(Comparator.comparingInt(EntityLanguageSpreaderSnapshot::getSpreaderCount))
                .orElseThrow();
        int shortfall = best.getSpreaderCount() - ownSnapshot.getSpreaderCount();
        if (shortfall < SPREADER_GAP_MIN_ABSOLUTE_SHORTFALL) {
            return null;
        }
        ManagedEntity compEntity = entityRepository.findById(best.getEntityId()).orElse(null);
        if (compEntity == null) {
            return null;
        }

        List<SpreaderProfile> ownProfiles = readSpreaderProfiles(ownSnapshot);
        List<SpreaderProfile> compProfiles = readSpreaderProfiles(best);
        Set<String> ownHandles = ownProfiles.stream()
                .map(SpreaderProfile::globalUserId)
                .collect(Collectors.toSet());
        // The outreach opportunity: comparable movie's top spreaders for this language who aren't
        // already among this movie's own - concrete new prospects, not the ones already talking about it.
        List<SpreaderProfile> outreachTargets = compProfiles.stream()
                .filter(p -> p.globalUserId() != null && !p.globalUserId().isBlank())
                .filter(p -> !ownHandles.contains(p.globalUserId()))
                .sorted(Comparator.comparingLong(SpreaderProfile::totalViews).reversed())
                .toList();
        List<String> topHandles = outreachTargets.stream()
                .map(SpreaderProfile::globalUserId)
                .limit(TOP_HANDLES_LIMIT)
                .toList();
        List<RecommendedActionUser> relevantUsers = outreachTargets.stream()
                .limit(MAX_RELEVANT_USERS)
                .map(p -> new RecommendedActionUser(p.globalUserId(), p.primaryPlatform(), p.profileUrl()))
                .toList();

        List<String> facts = new ArrayList<>();
        facts.add(String.format(Locale.ROOT,
                "%s (similar budget, within +/-%.0f%%) had %d of the top %s-language spreaders talking about it, " +
                        "but only %d %s-language spreader(s) are currently talking about this movie.",
                compEntity.getName(), BUDGET_RANGE_FRACTION * 100, best.getSpreaderCount(), language,
                ownSnapshot.getSpreaderCount(), language));
        if (!topHandles.isEmpty()) {
            facts.add("Reach out to more of the " + language + "-language spreader(s) below to extend reach: "
                    + String.join(", ", topHandles) + ".");
        }

        int confidence = spreaderGapConfidence(compSnapshots.size());
        return new RecommendedActionCandidate(
                "top-spreader-gap-" + slug(language),
                language + " top-spreader coverage gap",
                RecommendedActionCategory.MEDIUM_IMPACT, confidence,
                PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS,
                buildWindowLabel(PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS),
                facts, topHandles, relevantUsers);
    }

    static int spreaderGapConfidence(long comparableMovieCount) {
        if (comparableMovieCount >= SPREADER_GAP_TIER_HIGH) {
            return SPREADER_GAP_CONFIDENCE_HIGH;
        }
        if (comparableMovieCount >= SPREADER_GAP_TIER_MID) {
            return SPREADER_GAP_CONFIDENCE_MID;
        }
        return SPREADER_GAP_CONFIDENCE_LOW;
    }

    private List<SpreaderProfile> readSpreaderProfiles(EntityLanguageSpreaderSnapshot snapshot) {
        try {
            SpreaderProfile[] profiles = objectMapper.readValue(snapshot.getSpreadersJson(), SpreaderProfile[].class);
            return List.of(profiles);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== Cumulative view-count gap + viral-seed outreach (this platform's own view
    // totals, see MentionRepository.findTotalViewsForEntity(s), and ViralSeedSyncService's periodic
    // AuraMath viral-seeds sweep, see EntityViralSeedSnapshot) ====================

    // Compares this movie's own cumulative view count against budget-comparable movies' totals - the
    // same "similar movie" pool topSpreaderGapCandidates uses above, not a genre/language comp. When
    // one or more comparable movies are meaningfully ahead (VIEW_GAP_MIN_PCT_MORE_FRACTION or more), up
    // to VIEW_GAP_MAX_EXAMPLES of them are cited by name with their real view counts, and the outreach
    // roster offered is that movie's viral-seed accounts (from its EntityViralSeedSnapshot) who haven't
    // already commented on this movie - concrete new prospects, not accounts already talking about it.
    // Which qualifying comparable movie(s) get cited is reselected once per day (seeded by entity id +
    // the current day), not always the single biggest gap, so a re-run of RecommendedActionsService's
    // periodic refresh cycle can surface a different real example over time instead of repeating the
    // same one indefinitely. No budget on file - see hasRealBudget - means there's no real budget to
    // scope comparable movies by, so this generator produces nothing at all, same reasoning as
    // topSpreaderGapCandidates. A zero own-view-count also produces nothing - there's no honest
    // percentage to compute against a zero denominator.
    private RecommendedActionCandidate viralSeedViewCountGapCandidate(ManagedEntity entity) {
        if (!hasRealBudget(entity.getBudget())) {
            return null;
        }
        long ownViews = mentionRepository.findTotalViewsForEntity(entity.getId());
        if (ownViews <= 0) {
            return null;
        }

        double minBudget = entity.getBudget() * (1 - BUDGET_RANGE_FRACTION);
        double maxBudget = entity.getBudget() * (1 + BUDGET_RANGE_FRACTION);
        List<ManagedEntity> comparableMovies = entityRepository
                .findByTypeAndBudgetBetweenAndIdNot(MOVIE_TYPE, minBudget, maxBudget, entity.getId()).stream()
                .filter(m -> hasRealBudget(m.getBudget()))
                .toList();
        if (comparableMovies.isEmpty()) {
            return null;
        }
        List<Long> comparableIds = comparableMovies.stream().map(ManagedEntity::getId).toList();
        Map<Long, ManagedEntity> comparableById = comparableMovies.stream()
                .collect(Collectors.toMap(ManagedEntity::getId, m -> m));

        Map<Long, Long> viewsByComparable = new LinkedHashMap<>();
        for (Object[] row : mentionRepository.findTotalViewsForEntities(comparableIds)) {
            Long id = ((Number) row[0]).longValue();
            long views = row[1] == null ? 0L : ((Number) row[1]).longValue();
            viewsByComparable.put(id, views);
        }

        // Comparable movies at least VIEW_GAP_MIN_PCT_MORE_FRACTION ahead of this movie's own view
        // count, ranked by the size of that gap - the strongest real precedent first, before the daily
        // reselection below narrows it down.
        List<Long> qualifyingIds = viewsByComparable.entrySet().stream()
                .filter(e -> e.getValue() >= ownViews * (1 + VIEW_GAP_MIN_PCT_MORE_FRACTION))
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        if (qualifyingIds.isEmpty()) {
            return null;
        }

        List<Long> shuffled = new ArrayList<>(qualifyingIds);
        Collections.shuffle(shuffled, new Random(entity.getId() * 1_000_003L + LocalDate.now(clock).toEpochDay()));
        List<Long> chosenIds = shuffled.stream().limit(VIEW_GAP_MAX_EXAMPLES).toList();

        Map<Long, EntityViralSeedSnapshot> snapshotById = viralSeedSnapshotRepository.findByEntityIdIn(chosenIds)
                .stream().collect(Collectors.toMap(EntityViralSeedSnapshot::getEntityId, s -> s));

        List<String> facts = new ArrayList<>();
        Map<String, ViralSeedLookupService.ViralSeed> outreachByAuthor = new LinkedHashMap<>();
        for (Long compId : chosenIds) {
            ManagedEntity compEntity = comparableById.get(compId);
            long compViews = viewsByComparable.get(compId);
            double pctMore = ((double) compViews - ownViews) / ownViews * 100.0;
            facts.add(String.format(Locale.ROOT,
                    "%s has a cumulative view count of %,d, which is %.0f%% more than this movie (similar " +
                            "budget, within +/-%.0f%%). Reach out to more viral seeds to spread the impact of " +
                            "this movie to more audience.",
                    compEntity.getName(), compViews, pctMore, BUDGET_RANGE_FRACTION * 100));

            EntityViralSeedSnapshot snapshot = snapshotById.get(compId);
            if (snapshot == null) {
                continue;
            }
            for (ViralSeedLookupService.ViralSeed seed : readViralSeeds(snapshot)) {
                if (seed.author() == null || seed.author().isBlank() || mentionRepository
                        .existsByManagedEntityIdAndAuthorIgnoreCase(entity.getId(), seed.author())) {
                    continue;
                }
                outreachByAuthor.putIfAbsent(seed.author(), seed);
            }
        }

        List<ViralSeedLookupService.ViralSeed> outreachTargets = new ArrayList<>(outreachByAuthor.values());
        List<String> topHandles = outreachTargets.stream()
                .map(ViralSeedLookupService.ViralSeed::author)
                .limit(TOP_HANDLES_LIMIT)
                .toList();
        List<RecommendedActionUser> relevantUsers = outreachTargets.stream()
                .limit(MAX_RELEVANT_USERS)
                .map(s -> new RecommendedActionUser(s.author(), s.primaryPlatform(), s.profileUrl()))
                .toList();
        if (!topHandles.isEmpty()) {
            facts.add("Potential viral-seed account(s) who haven't yet commented on this movie: "
                    + String.join(", ", topHandles) + ".");
        }

        int confidence = viewGapConfidence(qualifyingIds.size());
        return new RecommendedActionCandidate(
                "viral-seed-view-count-gap",
                "Cumulative view-count gap vs. comparable movies",
                RecommendedActionCategory.MEDIUM_IMPACT, confidence,
                PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS,
                buildWindowLabel(PEER_TACTIC_WINDOW_START_DAYS, PEER_TACTIC_WINDOW_END_DAYS),
                facts, topHandles, relevantUsers);
    }

    static int viewGapConfidence(long qualifyingComparableMovieCount) {
        if (qualifyingComparableMovieCount >= VIEW_GAP_TIER_HIGH) {
            return VIEW_GAP_CONFIDENCE_HIGH;
        }
        if (qualifyingComparableMovieCount >= VIEW_GAP_TIER_MID) {
            return VIEW_GAP_CONFIDENCE_MID;
        }
        return VIEW_GAP_CONFIDENCE_LOW;
    }

    private List<ViralSeedLookupService.ViralSeed> readViralSeeds(EntityViralSeedSnapshot snapshot) {
        try {
            ViralSeedLookupService.ViralSeed[] seeds = objectMapper.readValue(
                    snapshot.getSeedsJson(), ViralSeedLookupService.ViralSeed[].class);
            return List.of(seeds);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== Genre resolution ====================

    // genre is already a stored, client-populated field on ManagedEntity - read it directly, no LLM
    // classification needed. Blank/missing genre simply means the genre-dependent candidates below
    // are skipped (not backfilled with a guess).
    private String resolveGenre(ManagedEntity entity) {
        String genre = entity.getGenre();
        return (genre == null || genre.isBlank()) ? null : genre.trim();
    }

    // ==================== Shared helpers ====================

    private RecommendedActionCandidate factorCandidate(int factorNumber, String slug, int confidencePct,
                                                         int windowStartDays, int windowEndDays, List<String> facts) {
        return factorCandidate(factorNumber, slug, confidencePct, windowStartDays, windowEndDays, facts, List.of(),
                List.of());
    }

    private RecommendedActionCandidate factorCandidate(int factorNumber, String slug, int confidencePct,
                                                         int windowStartDays, int windowEndDays, List<String> facts,
                                                         List<String> exampleHandles) {
        return factorCandidate(factorNumber, slug, confidencePct, windowStartDays, windowEndDays, facts,
                exampleHandles, List.of());
    }

    private RecommendedActionCandidate factorCandidate(int factorNumber, String slug, int confidencePct,
                                                         int windowStartDays, int windowEndDays, List<String> facts,
                                                         List<String> exampleHandles,
                                                         List<RecommendedActionUser> relevantUsers) {
        BoxOfficeFactorCatalog.FactorDefinition def = BoxOfficeFactorCatalog.byNumber(factorNumber);
        return new RecommendedActionCandidate(
                "factor-" + factorNumber + "-" + slug,
                def.name(), categorize(def), confidencePct, windowStartDays, windowEndDays,
                buildWindowLabel(windowStartDays, windowEndDays), facts, exampleHandles, relevantUsers);
    }

    private RecommendedActionCandidate factorCandidateFromWindowTable(int factorNumber, String slug,
                                                                        int confidencePct, String fact) {
        return factorCandidateFromWindowTable(factorNumber, slug, confidencePct, List.of(fact));
    }

    private RecommendedActionCandidate factorCandidateFromWindowTable(int factorNumber, String slug,
                                                                        int confidencePct, List<String> facts) {
        return factorCandidateFromWindowTable(factorNumber, slug, confidencePct, facts, List.of());
    }

    private RecommendedActionCandidate factorCandidateFromWindowTable(int factorNumber, String slug,
                                                                        int confidencePct, List<String> facts,
                                                                        List<String> exampleHandles) {
        return factorCandidateFromWindowTable(factorNumber, slug, confidencePct, facts, exampleHandles, List.of());
    }

    private RecommendedActionCandidate factorCandidateFromWindowTable(int factorNumber, String slug,
                                                                        int confidencePct, List<String> facts,
                                                                        List<String> exampleHandles,
                                                                        List<RecommendedActionUser> relevantUsers) {
        WindowSpec window = WINDOW_BY_FACTOR.get(factorNumber);
        return factorCandidate(factorNumber, slug, confidencePct, window.startDays(), window.endDays(), facts,
                exampleHandles, relevantUsers);
    }

    static RecommendedActionCategory categorize(BoxOfficeFactorCatalog.FactorDefinition def) {
        double midpoint = (Math.abs(def.low()) + Math.abs(def.high())) / 2.0;
        if (midpoint >= HIGH_IMPACT_THRESHOLD) {
            return RecommendedActionCategory.HIGH_IMPACT;
        }
        if (midpoint >= MEDIUM_IMPACT_THRESHOLD) {
            return RecommendedActionCategory.MEDIUM_IMPACT;
        }
        return RecommendedActionCategory.LOW_IMPACT;
    }

    static String buildWindowLabel(int startDays, int endDays) {
        if (startDays <= 0 && endDays >= 0 && (endDays - startDays) <= RELEASE_WEEK_SPAN_DAYS) {
            return "Release week";
        }
        if (endDays <= 0) {
            return formatRelativeRange(-endDays, -startDays) + " before release";
        }
        if (startDays >= 0) {
            return formatRelativeRange(startDays, endDays) + " after release";
        }
        return formatRelativeRange(Math.abs(startDays), Math.abs(endDays)) + " around release";
    }

    private static String formatRelativeRange(int lowDays, int highDays) {
        if (lowDays % 7 == 0 && highDays % 7 == 0) {
            int lowWeeks = lowDays / 7;
            int highWeeks = highDays / 7;
            if (lowWeeks == highWeeks) {
                return lowWeeks + (lowWeeks == 1 ? " week" : " weeks");
            }
            return lowWeeks + "-" + highWeeks + " weeks";
        }
        if (lowDays == highDays) {
            return lowDays + (lowDays == 1 ? " day" : " days");
        }
        return lowDays + "-" + highDays + " days";
    }
}
