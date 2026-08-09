This is Phase 1 of 2 for a new "Recommended Actions" panel on the movie Command Center: a list of
marketing actions the movie's marketing team should take, grouped into HIGH_IMPACT / MEDIUM_IMPACT
/ LOW_IMPACT categories, each with a title, a reason grounded in real data, a confidence score
(0-100), and a release-relative timing window. Phase 1 (this prompt) builds ONLY the fully-numeric
candidate-generation logic — no LLM call, no caching, no controller endpoint yet. Phase 2 (a
separate follow-up prompt, after this one is reviewed) adds an LLM step that selects from and adds
prose to these candidates, plus the cache/controller/scheduling plumbing. Do not build Phase 2's
pieces now — stop once Phase 1's deliverable (below) is implemented and tested.

HARD CONSTRAINT, more important than anything else in this brief: nothing in Phase 1 involves an
LLM. Every number — category, confidencePct, windowStartDaysFromRelease, windowEndDaysFromRelease
— must be computed here in plain Java from real queries against real tables, using explicit
documented rubrics (fixed constants), never a value asked of a model. This constraint is the entire
point of splitting the work this way: Phase 2 will only ever be allowed to select from and narrate
the candidates Phase 1 produces, so every number in the final feature traces back to code written
in this phase.

## Deliverable

A new interface `RecommendedActionCandidateService` with implementation
`RecommendedActionCandidateServiceImpl`, following this codebase's interface+impl convention (see
LLMService/LLMServiceImpl, BoxOfficeBaselineService/BoxOfficeBaselineServiceImpl) — specifically so
Phase 2's tests can mock this service as an interface rather than a concrete class (this project's
Java 25 setup breaks Mockito's inline mocking of concrete classes; only interfaces get mocked, per
the existing test suite's convention). Single public method:
`List<RecommendedActionCandidate> buildCandidateActions(Long entityId)` — pure Java, DB-only, no
network/LLM call, fully unit-testable by mocking repositories.

`RecommendedActionCandidate` (new record/DTO): factor name, category (enum HIGH_IMPACT/
MEDIUM_IMPACT/LOW_IMPACT), confidencePct (int 0-100), windowStartDaysFromRelease/
windowEndDaysFromRelease (signed ints — negative before release, positive after), windowLabel
(human-readable, derived from the two day offsets, e.g. "6-8 weeks before release" / "Release week"
/ "2 weeks after release"), a stable candidateId (String or int — Phase 2 will reference this), and
the specific supporting facts/numbers behind it as a short list of strings (e.g. "18 comparable
Kannada action-genre releases averaged ₹42Cr", "7 Tier-1/2 positive-sentiment accounts identified")
— these strings are what Phase 2 will be allowed to cite, so make them precise and self-contained.

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
box-office factor list. A factor only becomes a candidate when there's enough real backing data for
it (see minimum-sample-size rules below) — a factor with no supporting data for this entity simply
produces no candidate, it is never included with a placeholder/guessed number.

Teaser/Trailer Impact and First-Single Timing are SERVER_COMPUTED in the catalog — do not have the
LLM (there is no LLM in Phase 1 anyway) or any heuristic guess their timing. Reuse the exact
calibrated thresholds already live in BoxOfficeBacktestWorkerImpl
(src/main/java/com/aura/service/service/BoxOfficeBacktestWorkerImpl.java, constants near the top of
the class): trailer/teaser released fewer than SHORT_WINDOW_DAYS=14 days before release is too late
(-15% penalty); OPTIMAL_MIN_DAYS_46=30 to OPTIMAL_MAX_DAYS_46=45 days before release is the optimal
window (+25% bonus); first single released OPTIMAL_MIN_DAYS_47=42 to OPTIMAL_MAX_DAYS_47=56 days
before release (6-8 weeks) is optimal (+25% bonus, no defined penalty outside it). Generate the
"release your trailer/teaser" and "release your first single" candidates by comparing
entity.getReleaseDate() against these exact day windows — this is deterministic calendar math, not
a statistical estimate.

## Historical grounding (movies_data_collection)

Ground every candidate's supporting facts in real historical numbers, the same way
BoxOfficeBaselineServiceImpl and MovieAudienceServiceImpl already query movies_data_collection
(columns confirmed in use elsewhere: movie_name, genre, language, release_date, budget, revenue,
directors, imdb_rating, teaser_release_date, teaser_views, trailer_release_date, trailer_views).
Query movies_data_collection for comparable movies to this entity — same genre + language (see
BoxOfficeBaselineServiceImpl's CONCEPT_DENSITY_SQL: COUNT(*) WHERE genre = :genre AND
LOWER(language) = LOWER(:language) AND release_date BETWEEN ... as the pattern to copy, using the
same @PersistenceContext EntityManager native-query style) and a budget within ±50% (see
MovieAudienceServiceImpl's BUDGET_RANGE_FRACTION pattern) — and summarize their aggregate outcomes
(avg revenue, count) as supporting facts. If a genre/language/budget combination has too few
historical comps, that candidate is simply not produced — same spirit as CommandCenterSummaryService
omitting vsYesterday sentiment below MIN_MENTIONS_FOR_DAILY_DELTA. For India-specific reasoning,
also reuse the entity's already-populated industry field (e.g. Sandalwood/Kollywood/Bollywood) —
industry and language are already stored and correctly mapped at entity-creation time.

Best release day/window for this genre+language: no existing query computes this — write a new one,
following the same raw-SQL-in-service style as CONCEPT_DENSITY_SQL/FRANCHISE_MATCH_SQL (native
query, WHERE genre = :genre AND LOWER(language) = LOWER(:language), release_date not null,
revenue not null), grouped by day-of-week (EXTRACT(DOW FROM release_date)) and/or month, with
AVG(revenue) and COUNT(*) per bucket, so the supporting fact can say "movies of this genre/language
released on a Friday during [month] averaged ₹X revenue across N comps." Enforce the same
minimum-sample-size rule as above before producing this candidate. Named festival/holiday windows
(Diwali, Pongal, Eid, Christmas, Sankranti, etc.) near the entity's actual release date can be
flagged by simple date-proximity logic in Java (no table backs specific holiday dates, so a small
hardcoded reference table of major Indian release-relevant holidays by year/date is reasonable to
add here) — pair a detected holiday proximity with Factor 61's (Holiday Release Windows) already-
defined +40-60% impact range from BoxOfficeFactorCatalog, don't invent a new percentage.

Read entity.getGenre() directly — it's already a stored, client-populated field on ManagedEntity
(src/main/java/com/aura/service/entity/ManagedEntity.java), do NOT re-derive it from synopsis. Only
if getGenre() is null/blank should you fall back to classifying it from entity.getSynopsis() (a
short, single LLM-parsed classification call is acceptable here even though the rest of Phase 1 is
LLM-free — this is filling a missing categorical field from the entity's own description, not
generating a marketing number, so it doesn't violate the "no invented numbers" constraint; keep it
narrowly scoped, don't build a whole pipeline for what should be a rare gap-fill).

## Evangelists, reach, and audience-timing — reuse real data

Ground reach/evangelist/fanbase-mobilization and posting-time candidates in real platform data —
this codebase already has the building blocks, reuse them rather than re-implementing:

- Evangelist/influencer candidates: TopSpreaderLookupService.getSpreaderProfiles(keyword) (proxies
  AuraMath's /api/marketing/top-50-spreaders/{keyword}) returns real SpreaderProfile(globalUserId,
  primaryPlatform, influenceTier) records per tracked keyword. Call it for this entity's
  EntityKeyword list (same as MobilizeAlliesService.fetchSpreaderProfiles does), then filter to
  authors who are predominantly positive-sentiment toward THIS entity using
  MentionRepository.countSentimentByAuthorsForEntity(entityId, authorIds) — copy
  MobilizeAlliesService.filterPredominantlyPositive's exact pos>neg && pos>=neu logic rather than
  reinventing the threshold. Rank by positive-mention count then influenceTier (reuse
  MobilizeAlliesService.tierRank's TIER_1..TIER_4 ordering). The resulting count (e.g. "8 Tier-1/2
  positive-sentiment accounts identified") is the supporting fact for a Core-Fanbase-Mobilization or
  Influencer-Driven-Promotion candidate. Do NOT generate outreach DM text here (that's
  MobilizeAlliesService's job for a specific mention) — this is just the count/tier breakdown as
  evidence.

- Reach/impressions: ImpressionsResolver only resolves real numbers for Platform.X
  (x_posts.views_count) — Reddit/Instagram/YouTube always resolve to "NA" (no impression column
  exists for them). When citing "this movie's pre-release reach," sum X impressions only and label
  it explicitly as X-platform reach, not total cross-platform reach.

- Historical ally-mobilization lift: MobilizeAction (entityId, allyCount, createdAt) logs past
  mobilization events across all managed entities. For entities sharing this movie's budget tier
  (±50%) and language/genre, look up their MobilizeAction history (add a query —
  MobilizeActionRepository currently only has findByMentionId/findByUserId, add a findByEntityIdIn
  or similar) and measure the mention-count delta in the N days following each event (via
  MentionRepository, comparable window before/after createdAt) to produce a real "ally mobilization
  events of this scale correlated with an Nx mention-volume lift for comparable movies" statistic.
  If there isn't enough comparable history, omit this candidate/fact entirely rather than inventing
  a multiplier.

- Peak audience-activity hours: do not recompute this from scratch — DashboardService.
  getHourlyActivity(entityId, period, language, industry, state) (backing the existing
  /api/dashboard/{entityId}/hourly-activity endpoint, ungated) already returns
  HourlyActivityResponse.hourlyDistribution (Map<Integer hourOfDay, Long activeUserCount>). Call it
  for this entity (a reasonable TimePeriod such as the last 30 days; pass language/industry/state as
  null to scope to just this entity's own mentions, or pass the entity's own language/industry if
  its own mention volume is too sparse) and pick the top 2-3 hours by count as the "peak engagement
  window" supporting fact. Do not build a new hour-of-day query when this one already exists.

## Category, confidence, and timing rubrics — the core of this phase

Compute category and confidencePct using explicit, documented rubrics — named constants, not magic
numbers, the same way BoxOfficeBacktestWorkerImpl names SHORT_WINDOW_DAYS/OPTIMAL_MIN_DAYS_46
instead of inlining them:

- category: derive from the factor's BoxOfficeFactorCatalog impact range midpoint
  ((|low|+|high|)/2): >= 0.25 -> HIGH_IMPACT, >= 0.12 -> MEDIUM_IMPACT, else LOW_IMPACT. Name these
  thresholds (e.g. HIGH_IMPACT_THRESHOLD, MEDIUM_IMPACT_THRESHOLD).
- confidencePct: tiered off the strength of the evidence backing that specific candidate, as a named
  constant table per evidence source:
  - SERVER_COMPUTED calendar-rule candidates (trailer/teaser timing, first-single timing) — this is
    deterministic calibrated math, not a statistical estimate — fixed high confidence (e.g. 90).
  - movies_data_collection-backed candidates — sample-size-tiered (e.g. 5-14 comps -> 55, 15-29 ->
    70, 30+ -> 85; fewer than 5 -> no candidate at all, per the minimum-sample-size rule above).
  - evangelist-backed candidates — qualifying-account-count-tiered (e.g. 1-3 accounts -> 50, 4-7 ->
    65, 8+ -> 80).
  - peak-audience-hour candidates — tiered off the total active-user sample size backing the hourly
    distribution, same spirit as above.
  Pick your own exact numbers if these don't feel right, but they must be fixed constants applied by
  a deterministic rule — there is no LLM in this phase to ask, and there won't be one asked for this
  in Phase 2 either.
- windowStartDaysFromRelease/windowEndDaysFromRelease: for the two SERVER_COMPUTED factors, this is
  the exact calibrated day range above. For every other factor, define a constants table of typical
  execution windows (a Map<String factorName, WindowSpec(startDays, endDays)>, same spirit as
  SHORT_WINDOW_DAYS/OPTIMAL_MIN_DAYS_46 — sourced from standard marketing-industry lead times, e.g.
  promotional tours ~4-8 weeks pre-release, countdown posters ~final 1-2 weeks, cross-
  promotion/brand partnerships locked in ~8-12 weeks pre-release, post-release word-of-mouth and
  social discourse monitoring ~weeks 1-4 after release, screen-count/P&A negotiation ~6-10 weeks
  pre-release. Fill in the rest of the curated factor list using the same kind of standard-practice
  reasoning — this is a lookup table you author once as a developer, not something computed per
  movie.

## Testing

Write RecommendedActionCandidateServiceImplTest covering, with mocked repositories (interfaces
only, per this project's Mockito/Java-25 constraint — never mock ManagedEntity or other concrete
JPA entities, construct real instances instead):
- category threshold boundaries (exactly at 0.25 and 0.12 midpoints),
- confidence tier boundaries for each evidence source (exactly at each sample-size/count cutoff),
- the minimum-sample-size omission rule (below-threshold data produces no candidate, not a
  low-confidence one),
- the calibrated trailer/teaser/first-single day-window constants applied correctly on both sides of
  each boundary (13 vs 14 vs 15 days, etc.),
- the evangelist positive-sentiment filter (pos>neg && pos>=neu) and tier ranking,
- the genre-fallback-to-synopsis path only triggering when getGenre() is blank/null,
- that every RecommendedActionCandidate produced has non-null category/confidencePct/window fields
  and at least one supporting fact string.

Do not build the cache entity, controller endpoint, LLM prompt, or @Scheduled refresh in this
phase — those belong to Phase 2, which will be handed to you as a separate follow-up prompt once
this one is reviewed. Run the full test suite and confirm it passes before considering Phase 1 done.
