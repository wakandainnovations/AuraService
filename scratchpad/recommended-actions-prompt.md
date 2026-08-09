Build the backend for a "Recommended Actions" panel on the movie Command Center: a list of
marketing actions the movie's marketing team should take, grouped into HIGH_IMPACT / MEDIUM_IMPACT
/ LOW_IMPACT categories. Each action needs: a title, a reason grounded in real data (not a vague
LLM platitude), a confidence score (0-100) that the action will help box office performance, and a
release-relative timing window (e.g. "6-8 weeks before release", "release week", "2 weeks after
release" — post-release actions are valid too).

HARD CONSTRAINT, more important than anything else in this brief: the LLM must never invent, guess,
estimate, or compute a number. Every number that ends up in a persisted action — category,
confidencePct, windowStartDaysFromRelease, windowEndDaysFromRelease, and any count/percentage
mentioned in the reason text — is computed server-side, in Java, from real queries against real
tables, BEFORE the LLM is ever called. The LLM is not a numbers source; treat it purely as a
selection-and-phrasing step over a candidate list you've already fully computed (full design in the
"LLM's role" section below) — do NOT design this as "ask the LLM to output a JSON object with a
confidence field," because that reintroduces exactly the invented-number problem this section
exists to rule out.

Follow the exact architectural pattern already used twice in this codebase for LLM-backed panels
that need to be fast to read: CommandCenterSummaryService (+ CommandCenterSummaryCache) for
AI Summary/Today's Highlights, and AudiencePulseAspectsService (+ AudiencePulseAspectsCache) for
the People Love/Concerned chips. Read both end-to-end before writing anything — new code must
match their shape: entity → repository → service → DTO → controller endpoint, single JSON blob
per entity in a `*_cache` table (not one row per action), a @Scheduled(fixedDelayString = "...")
refresh loop that regenerates every ManagedEntity's row and logs+skips per-entity failures rather
than aborting, a `refresh=false` query param that falls back to on-demand generation on cache miss,
and the LLM prompt template stored in application.properties (see llm.prompt.generate.command.center.summary
and llm.prompt.generate.recommended.actions as the new key) with a placeholder token you .replace()
with a JSON facts blob you build server-side — never let the LLM invent data it wasn't handed.

## Factor catalog — reuse, don't re-derive

DO NOT re-derive the marketing factor catalog from scratch. BoxOfficeFactorCatalog
(src/main/java/com/aura/service/service/BoxOfficeFactorCatalog.java) already has ~90 factors with
name/Direction/impact-range(low,high)/Role, used by the box-office backtest prompt
(BoxOfficeBacktestWorkerImpl). Cross-reference it against these marketing-actionable factors and
reuse its numbers/names verbatim wherever they match (do not let two catalogs drift out of sync —
add a comment pointing back to BoxOfficeFactorCatalog like BoxOfficeBacktestWorkerImpl does):
Core Fanbase Mobilization Value, Lead Actor Screen Chemistry, Directorial Brand Equity, Off-Screen
Actor Controversy, Nostalgic Screen Reunions, Star Political Aspirations/Dialogue Placement,
Cameo Appearances of Iconic Stars, Use of Brand Extensions/Sequel Names, Star Attendance at
On-Ground Events, Micro-Video Social Media Campaigns, Influencer-Driven Promotions, Misleading
Trailer Marketing, High-Definition Promo/BTS Content, Strategic Use of Countdown Posters,
Cross-Promotion and Brand Partnerships, Global Promotional Tours, Holiday Release Windows, Direct
Box Office Clashes, Student Examination Schedules, Political Events and Elections, Major Sporting
Events, Academic Summer Vacation Windows, Extreme Weather Conditions, Theatrical Window/OTT
Release Strategy, Digital KDM Lockout, Minimum Guarantee Distribution, Outright Purchase
Territorial Sales, High Interest Rates on Film Finance, Multiplex Revenue Share Splits, Global
Subtitle/Dubbing Quality, Screen Count Allocation and Show Pacing, P&A Commitments, Joint
Production Partnerships, Producer Debt and Studio Solvency, Organic Word-of-Mouth (Post-Day 1),
Social Media Discourse and Meme Trends, Target Audience Alignment, Critical Review Ratings,
Fast-Tracked Online Ticket Booking Trends, Sensitivity of Cultural/Religious Portrayals, Audience
Fatigue with Repetitive Templates, Theatrical Communal Viewing Experience, Value-for-Money
Perception, Repeat Theatrical Viewership Value. Explicitly EXCLUDE catalog factors the marketing
team can't act on (VFX quality, BGM, editing pace, cinematography, cast chemistry as a creative
choice, runtime, CBFC/legal/tax factors) — this panel is marketing-team actions, not a general
box-office factor list.

Teaser/Trailer Impact and First-Single Timing are SERVER_COMPUTED in the catalog — do not have the
LLM rate or guess their timing. Reuse the exact calibrated thresholds already live in
BoxOfficeBacktestWorkerImpl (src/main/java/com/aura/service/service/BoxOfficeBacktestWorkerImpl.java,
constants near the top of the class): trailer/teaser released fewer than SHORT_WINDOW_DAYS=14 days
before release is too late (-15% penalty); OPTIMAL_MIN_DAYS_46=30 to OPTIMAL_MAX_DAYS_46=45 days
before release is the optimal window (+25% bonus); first single released OPTIMAL_MIN_DAYS_47=42 to
OPTIMAL_MAX_DAYS_47=56 days before release (6-8 weeks) is optimal (+25% bonus, no defined penalty
outside it). Generate the "release your trailer/teaser" and "release your first single" actions by
comparing entity.getReleaseDate() against these exact day windows — this is a deterministic,
server-computed action (like windowStart/End below), not something to ask the LLM to invent a
number for. If you want to enrich beyond the flat constant, MovieBacktestRow-style historical
teaser/trailer dates + views exist in movies_data_collection (teaser_release_date, teaser_views,
trailer_release_date, trailer_views columns, per BoxOfficeBacktestWorkerImpl's prompt placeholders)
— aggregating those for genre/language comps is a nice-to-have, but the flat calibrated window is
the minimum bar and must not be skipped or re-guessed by the LLM.

## Historical grounding (movies_data_collection)

Ground every action's "reason" in real historical numbers, not LLM free-association, the same way
BoxOfficeBaselineServiceImpl and MovieAudienceServiceImpl already query movies_data_collection
(columns confirmed in use elsewhere: movie_name, genre, language, release_date, budget, revenue,
directors, imdb_rating, teaser_release_date, teaser_views, trailer_release_date, trailer_views).
Before building the facts JSON handed to the LLM, query movies_data_collection for comparable
movies to this entity — same genre + language (see BoxOfficeBaselineServiceImpl's CONCEPT_DENSITY_SQL:
COUNT(*) WHERE genre = :genre AND LOWER(language) = LOWER(:language) AND release_date BETWEEN ...
as the pattern to copy) and a budget within ±50% (see MovieAudienceServiceImpl's
BUDGET_RANGE_FRACTION pattern) — and summarize their aggregate outcomes (avg revenue, count, maybe
budget-to-revenue ratio) into the facts block. If a genre/language/budget combination has too few
historical comps, say so in the facts rather than padding with an unreliable average, same spirit
as CommandCenterSummaryService omitting vsYesterday sentiment below MIN_MENTIONS_FOR_DAILY_DELTA.
For India-specific reasoning, also reuse the entity's already-populated industry field (e.g.
Sandalwood/Kollywood/Bollywood) — industry and language are already stored and correctly mapped at
entity-creation time.

Best release day/window for this genre+language: no existing query computes this — write a new one,
following the same raw-SQL-in-repository style as CONCEPT_DENSITY_SQL/FRANCHISE_MATCH_SQL (native
query, WHERE genre = :genre AND LOWER(language) = LOWER(:language), release_date not null,
revenue not null), grouped by day-of-week (EXTRACT(DOW FROM release_date)) and/or month, with
AVG(revenue) and COUNT(*) per bucket, so you can tell the LLM "movies of this genre/language
released on a Friday during [month] averaged X revenue across N comps" rather than letting it
guess a "best release day." Enforce the same minimum-sample-size rule as above before surfacing a
bucket. Named festival/holiday windows (Diwali, Pongal, Eid, Christmas, Sankranti, etc.) near the
entity's actual release date are general calendar knowledge the LLM can reason about directly (no
table backs this) — but the LLM must only use that knowledge to flag proximity to a known holiday
window near the specific release date given, not to invent an impact percentage; Factor 61 (Holiday
Release Windows, from BoxOfficeFactorCatalog) already supplies the +40-60% figure to cite.

Read entity.getGenre() directly — it's already a stored, client-populated field on ManagedEntity
(src/main/java/com/aura/service/entity/ManagedEntity.java), do NOT re-derive it from synopsis. Only
if getGenre() is null/blank should you fall back to classifying it from entity.getSynopsis() (a
short, single LLM-parsed classification call, or fold it into the main facts-building step — your
call, but don't build a whole separate genre-inference pipeline for what should be a rare gap-fill).

## Evangelists, reach, and audience-timing — reuse real data, don't let the LLM guess numbers

Ground reach/evangelist/fanbase-mobilization and posting-time actions in real platform data instead
of letting the LLM invent contact counts, reach multipliers, or "best time to post" guesses — this
codebase already has the building blocks, reuse them rather than re-implementing:

- Evangelist/influencer candidates: TopSpreaderLookupService.getSpreaderProfiles(keyword) (proxies
  AuraMath's /api/marketing/top-50-spreaders/{keyword}) returns real SpreaderProfile(globalUserId,
  primaryPlatform, influenceTier) records per tracked keyword. Call it for this entity's
  EntityKeyword list (same as MobilizeAlliesService.fetchSpreaderProfiles does), then filter to
  authors who are predominantly positive-sentiment toward THIS entity using
  MentionRepository.countSentimentByAuthorsForEntity(entityId, authorIds) — copy
  MobilizeAlliesService.filterPredominantlyPositive's exact pos>neg && pos>=neu logic rather than
  reinventing the threshold. Rank by positive-mention count then influenceTier (reuse
  MobilizeAlliesService.tierRank's TIER_1..TIER_4 ordering). The resulting count (e.g. "8 Tier-1/2
  positive-sentiment accounts identified") is what a Core-Fanbase-Mobilization or
  Influencer-Driven-Promotion action's reason should cite — a real number, not an LLM guess. You do
  NOT need to generate the outreach DM text here (that's MobilizeAlliesService's job for a specific
  mention) — Recommended Actions only needs the count/tier breakdown as evidence, not fabricate a
  reach multiplier like "will increase reach 3x" unless you compute it (see below).

- Reach/impressions: ImpressionsResolver only resolves real numbers for Platform.X
  (x_posts.views_count) — Reddit/Instagram/YouTube always resolve to "NA" (no impression column
  exists for them). When citing "this movie's pre-release reach," sum X impressions only and label
  it explicitly as X-platform reach, not total cross-platform reach — do not silently extrapolate
  across platforms that have no impression data.

- Historical ally-mobilization lift (the closest real proxy to "this worked for a similar movie"):
  MobilizeAction (entityId, allyCount, createdAt) logs past mobilization events across all managed
  entities. For entities sharing this movie's budget tier (±50%, same convention as
  MovieAudienceServiceImpl) and language/genre, look up their MobilizeAction history (you'll need to
  add a query — MobilizeActionRepository currently only has findByMentionId/findByUserId, add a
  findByEntityIdIn or similar) and measure the mention-count delta in the N days following each
  event (via MentionRepository, comparable window before/after createdAt) to produce a real "ally
  mobilization events of this scale correlated with an Nx mention-volume lift for comparable movies"
  statistic. If there isn't enough comparable history (too few MobilizeAction rows for that
  budget/language/genre bucket), omit the multiplier claim entirely rather than inventing one — same
  "omit rather than pad with unreliable data" rule as the movies_data_collection comps.

- Peak audience-activity hours ("what time of day is the audience most active"): do not recompute
  this from scratch — DashboardService.getHourlyActivity(entityId, period, language, industry,
  state) (backing the existing /api/dashboard/{entityId}/hourly-activity endpoint, ungated) already
  returns HourlyActivityResponse.hourlyDistribution (Map<Integer hourOfDay, Long activeUserCount>).
  Call it for this entity (a reasonable TimePeriod such as the last 30 days; pass language/industry/
  state as null to scope to just this entity's own mentions, or pass the entity's own language/
  industry if its own mention volume is too sparse to produce a meaningful hourly distribution) and
  pick the top 2-3 hours by count as the "peak engagement window" fact — feed that into actions
  about when to post countdown content, drop the trailer, schedule micro-video campaigns, etc.
  Do not build a new hour-of-day query when this one already exists and is already ungated on the
  same controller you're extending.

Do not have the LLM free-associate any of these numbers (evangelist count, X reach, mention-lift
multiplier, peak hours, best release day/window) — compute them server-side in the facts-building
step. As described next, these numbers never even reach the LLM as "things it could restate wrong"
in an open-ended way — they're attached to fully-formed candidate actions the LLM only selects from.

## LLM's role: select and phrase only — every number is computed before the LLM is called

This is the key architectural change from a naive "ask the LLM for JSON with a confidence field"
design. Split generation into two phases:

**Phase 1 — buildCandidateActions(entity, facts) — 100% Java, no LLM call.** For every marketing
factor in the curated subset of BoxOfficeFactorCatalog (see above) that has enough backing data to
be evaluated for this entity, construct a fully-populated candidate: factor name, category,
confidencePct, windowStartDaysFromRelease/windowEndDaysFromRelease, windowLabel, and the specific
supporting numbers (e.g. "N=18 comparable genre+language movies averaged ₹X revenue", "7 Tier-1/2
positive-sentiment accounts identified", "peak audience activity 8-10pm IST"). A factor with
insufficient data (below the minimum-sample-size thresholds already established above) simply does
not produce a candidate — it's never offered to the LLM at all, so the LLM can't be tempted to fill
the gap with a guess. This phase is where ALL the number-producing logic from the sections above
lives (historical comps, evangelist counts, reach, mention-lift, peak hours, calibrated
trailer/single timing).

Compute confidencePct and category here using explicit, documented rubrics — not vibes, and
definitely not the LLM:
- category: derive from the factor's BoxOfficeFactorCatalog impact range midpoint
  ((|low|+|high|)/2): >= 0.25 -> HIGH_IMPACT, >= 0.12 -> MEDIUM_IMPACT, else LOW_IMPACT. Document
  these thresholds as named constants (e.g. HIGH_IMPACT_THRESHOLD, MEDIUM_IMPACT_THRESHOLD) so
  they're easy to review/tune later, the same way BoxOfficeBacktestWorkerImpl names its thresholds
  rather than inlining magic numbers.
- confidencePct: tiered off the strength of the evidence backing that specific candidate, as a
  named constant table per evidence source — e.g. SERVER_COMPUTED calendar-rule candidates
  (trailer/teaser timing, first-single timing) are deterministic calibrated math, not a statistical
  estimate, so they get a fixed high confidence constant (e.g. 90); movies_data_collection-backed
  candidates get a sample-size-tiered confidence (e.g. 5-14 comps -> 55, 15-29 -> 70, 30+ -> 85,
  fewer than 5 -> no candidate at all per the minimum-sample-size rule); evangelist-backed
  candidates get a qualifying-account-count-tiered confidence (e.g. 1-3 -> 50, 4-7 -> 65, 8+ -> 80).
  Pick your own exact numbers if these don't feel right, but they must be fixed constants applied
  by a deterministic rule, never a value the LLM supplies.
- windowStartDaysFromRelease/windowEndDaysFromRelease: for the two SERVER_COMPUTED factors, this is
  the exact calibrated day range already given above. For every other factor, define a constants
  table of typical execution windows (a Map<String factorName, WindowSpec(startDays, endDays)>,
  same spirit as SHORT_WINDOW_DAYS/OPTIMAL_MIN_DAYS_46 — sourced from standard marketing-industry
  lead times, e.g. promotional tours ~4-8 weeks pre-release, countdown posters ~final 1-2 weeks,
  cross-promotion/brand partnerships locked in ~8-12 weeks pre-release, post-release word-of-mouth
  and social discourse monitoring ~weeks 1-4 after release, screen-count/P&A negotiation ~6-10 weeks
  pre-release. Fill in the rest of the curated factor list using the same kind of standard-practice
  reasoning — this is a lookup table you author once as a developer, not something computed per
  movie and not something the LLM decides.

**Phase 2 — an LLM call that can only select and phrase, never emit a number.** Send the LLM the
full candidate list from Phase 1 (each candidate already carrying its final category, confidence,
and window — presented as read-only context, not as a schema for the LLM to reproduce) plus the
entity's own facts (genre, language, industry, budget tier, days to release). Ask it to: (a) select
which candidates are genuinely relevant/worth surfacing for this specific movie (e.g. skip an
"off-screen controversy mitigation" candidate if nothing in the facts suggests controversy risk;
skip brand-partnership candidates for a budget tier where that's not realistic) — capping at a
sensible ~8-15 selected actions across all phases; and (b) write a natural, specific one-to-two
sentence "reason" for each selected candidate, using ONLY the numbers already present in that
candidate's supporting facts (it may restate them in prose, it must not add, alter, round
creatively, or introduce any new figure). The requested output JSON is therefore just an array of
{candidateId, reason} (optionally {candidateId, title} if you want the LLM to sharpen the title
too) — it must NOT include confidencePct, category, or any day-offset field; those never leave the
Java side, they're re-attached by candidateId after the LLM responds. Any candidateId the LLM
returns that doesn't match one you sent should be dropped (defensive parse, same spirit as
CommandCenterSummaryService defaulting an unrecognized highlight type rather than trusting the LLM's
output blindly). As a cheap defensive check, you may scan the returned reason text for digit
sequences and log a warning if one doesn't appear anywhere in that candidate's own facts — not a
hard requirement, but worth adding given how central "no invented numbers" is to this feature.

(c) Precedent framing: when a candidate's supporting facts already carry comparable-movie evidence
(the genre+language+budget comps from "Historical grounding" above, or the ally-mobilization-lift
comps from "Evangelists, reach, and audience-timing"), the reason should read as precedent —
"comparable [genre]/[language] releases have shown..." — not a generic platitude. This is the
feature's one legitimate form of "real movie precedent": grounded entirely in the app's own query
results already sitting in the candidate's facts, never a specific movie title recalled from the
LLM's training data. The LLM must not name, describe, or attribute an outcome to any specific movie
that isn't itself one of the entity's own tracked competitors/comps as given in the facts blob — a
named-but-ungrounded movie claim is exactly the invented fact the hard constraint at the top of this
document rules out, so do not add a "cite real movie examples" instruction to the prompt beyond this
aggregate-comps framing.

Marketing-concept coverage: don't restrict the panel's language to generic action categories. Where
a selected candidate's underlying factor is Organic Word-of-Mouth (Post-Day 1), Social Media
Discourse and Meme Trends, Micro-Video Social Media Campaigns, or Influencer-Driven Promotions, the
LLM may phrase the title/reason using concrete marketing-strategy language — e.g. "lean into a
high-concept campaign hook," "seed a community-led word-of-mouth push," "run a concept-driven teaser
built around the film's premise" — as long as the phrasing doesn't add a new number or fact beyond
that candidate's own supporting data. This is phrasing latitude only, not a new candidate-generation
pathway: "concept-driven marketing" is never itself a standalone factor or candidate unless it's
backed by one of the already-eligible, already-numeric factors above — do not have the LLM invent a
"high-concept gimmick" candidate that Phase 1 never produced.

## Data model

- New entity RecommendedActionsCache (uniqueConstraint on entity_id, mirroring
  CommandCenterSummaryCache): id, entityId, entityName, actionsJson (TEXT, serialized
  List<RecommendedActionItem> — the merged Phase 1 + Phase 2 result), daysToReleaseAtGeneration
  (int, informational), generatedAt (Instant).
- New repository RecommendedActionsCacheRepository with findByEntityId(Long).
- New DTO RecommendedActionItem: category, title, reason, confidencePct, relatedFactor,
  windowStartDaysFromRelease, windowEndDaysFromRelease, windowLabel — all of these are populated
  from the Phase 1 candidate (server-computed) and never come from parsing an LLM-supplied number;
  only `reason` (and optionally a refined `title`) is LLM-authored text layered onto an
  otherwise-Java-built record. windowStartDaysFromRelease/windowEndDaysFromRelease MUST be numeric
  (signed ints — negative before release, positive after), not a free-text label, so the service can
  filter deterministically — same reasoning as compareLabel() in CommandCenterSummaryService: never
  make the API layer parse prose to decide business logic. windowLabel is a human-readable string
  derived server-side from the two day offsets (e.g. "6-8 weeks before release" / "Release week" /
  "2 weeks after release").
- New DTO RecommendedActionsResponse: entityId, entityName, daysToRelease (today vs
  entity.releaseDate, signed int, null if entity has no releaseDate), actions (the list, already
  filtered — see below), generatedAt.

## Service

RecommendedActionsService, patterned exactly on AudiencePulseAspectsService's
@Transactional getCachedOrGenerate/regenerateAndStore/persist/toGeneratedContent shape, with
generate() implementing the two-phase design above:
- generate(): buildCandidateActions(entity) (Phase 1, pure Java, produces fully-numeric candidates)
  -> selectAndPhraseWithLlm(candidates, facts) (Phase 2, one LLM call using the
  llm.prompt.generate.recommended.actions template) -> merge the LLM's {candidateId, reason}
  selections back onto their full candidate records by id -> persist the merged list. If the LLM
  call fails or returns nothing usable, a reasonable fallback is to persist the candidates
  unfiltered/unphrased with a generic reason built from their own facts (e.g. "N comparable
  [genre]/[language] releases support this") rather than surfacing nothing — your call, but document
  whichever fallback you pick.
- getRecommendedActions(entityId, refresh, boolean allPhases) reads/regenerates the cached plan,
  then by default filters to actions whose [windowStartDaysFromRelease, windowEndDaysFromRelease]
  currently contains today's signed day-offset from entity.releaseDate (compute this server-side,
  clock-injected like the existing services use Clock — do not use LocalDate.now() directly, for
  testability). allPhases=true (query param) returns the whole plan ungrouped/unfiltered so the
  marketing team can see the full campaign roadmap, not just what's due now.
- Entities with no releaseDate: cannot compute a current window, so return the full plan
  (equivalent to allPhases=true) rather than an empty list — decide and document this fallback
  clearly with a comment, don't leave it implicit.
- @Scheduled refresh: since the underlying facts (genre/budget/historical comps) change rarely but
  the *current phase* changes daily, you don't need to regenerate the LLM plan more than once a
  day — use @Scheduled(fixedDelayString = "PT24H") rather than copying the PT6H used for
  sentiment-driven panels; explain the difference in a comment the way the existing services
  explain their own cadence.

## Controller

Add to DashboardController (same file as ai-summary/todays-highlights/audience-pulse-aspects),
following their exact shape — @GetMapping("/{entityId}/recommended-actions"), assertOwned(entityId)
first, refresh + allPhases query params (both defaultValue "false"), delegate to the service,
ResponseEntity.ok(...). Do not add feature-gating (AGGREGATED_INTEL etc.) unless asked — the
sibling panels on this same controller are ungated. Even though this endpoint internally reuses
DashboardService.getHourlyActivity, that method is itself already ungated on the same controller,
so this stays consistent — do not accidentally pull in a gated dependency (e.g. the
/api/marketing/audience-patterns feature, which IS gated behind AGGREGATED_INTEL) for the
peak-hours fact.

## Prompt property

Add llm.prompt.generate.recommended.actions to application.properties next to the other
llm.prompt.* entries, following their multi-line backslash-continuation string style. This prompt
backs Phase 2 only (selection + phrasing) — it must NOT ask the LLM to output confidencePct,
category, or any day-offset field; the requested JSON schema is just an array of
{candidateId, reason} (optionally title). Instruct the LLM explicitly: it is choosing from the
supplied candidate list, not generating new ones; every reason must be traceable to numbers already
present in that candidate's own facts; it must return the candidateId unchanged so the response can
be merged back onto the full server-computed record; and it should select a realistic number of
actions (roughly 8-15) spanning pre-release, release-week, and post-release, not just front-load
everything at T-8-weeks. State outright, the same way CommandCenterSummaryService's prompt does,
that it must not invent any fact, number, or statistic beyond what's in the candidate list.
Additionally instruct it: when a candidate's facts include comparable-movie/precedent data, frame
the reason in precedent terms ("comparable movies in this genre/language have shown...") using only
the aggregate comps figures already provided — it must never name a specific movie title unless that
title is itself one of the entity's own listed competitors in the facts blob; and where the
candidate's factor is word-of-mouth, social-discourse, or influencer/micro-video driven, it may
describe the action using concrete marketing-strategy language such as a concept-driven campaign, a
high-concept hook, or a community-led word-of-mouth push, without treating that language as license
to add any number or claim beyond the candidate's own facts.

## Testing

Write RecommendedActionsServiceTest and a controller test for the new endpoint, matching the style
of the existing sibling tests you can find for CheckpointImpactTest/DashboardControllerWhatsNewTest/
SentimentDeltaTest in src/test/java/com/aura/service/. Mock LLMService and the repositories
(interfaces) — do NOT attempt to mock concrete classes like ManagedEntity or the JPA entities
directly; this project's Java 25 setup breaks Mockito's inline mocking of concrete classes, so only
interfaces get mocked, following the pattern already used throughout the existing test suite. Cover:
cache hit returns without calling the LLM, cache miss generates and persists, the day-offset window
filtering logic (this is the part most likely to have an off-by-one — test the boundary days
explicitly), the no-releaseDate fallback, the evangelist-count/positive-sentiment-filter logic, and
the minimum-sample-size omission rule for historical comps and mention-lift multipliers. Specifically
also test Phase 1 in isolation with no LLM involved at all — buildCandidateActions() should be fully
unit-testable as pure Java (given fixed repository-mock data, assert the exact confidencePct/
category/window values it produces) — and test that Phase 2's merge step drops any candidateId the
mocked LLMService returns that wasn't in the candidate list it was given, and that a
mocked-LLM-failure still returns a usable (fallback) response rather than throwing.

After implementing, run the full test suite and confirm it passes before considering this done.
