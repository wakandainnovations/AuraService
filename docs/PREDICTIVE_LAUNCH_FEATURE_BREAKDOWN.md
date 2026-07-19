# Predictive Launch Intelligence — Feature Breakdown

A build plan for box-office prediction, release-date optimization, promo-timeline
recommendation, influencer discovery, and prescriptive "what to change / whom to cast / where to
spend" guidance, decomposed into **17 sequenced features** across the four services in this
workspace. Each feature names its target repo, cites the exact existing code it builds on, and
ends with a ready-to-paste **Claude Code prompt**.

> **Build order matters less across repos than within them.** F1 is foundational for
> everything that writes to the feature store (F1b, F8, F9, F10, F11, F12). F2/F3 are foundational
> for F4. F6 is foundational for F7. F9 is foundational for F11. F13 is foundational for F14.
> Features in *different* repos with no listed dependency can be built in parallel by running two
> Claude Code sessions in two directories.
>
> **F1b resolves a scoping error found after the first pass:** factors 63–65, 67, 71–80, and
> 82–87/90 were originally treated as future crawl targets. They aren't — most are private
> business data or domains (exam/election calendars, legal disputes) with no stable free source.
> F1b replaces "crawl it eventually" with a direct Production House entry surface for exactly
> those ~21 factors, and F2's scope was narrowed accordingly (holidays/festivals only, not
> exams/elections/sports).
>
> **F11–F16 close two gaps found after the first pass:** F1–F10 answers *when* well (release
> date, promo timing) but was weak on *whom to cast* and *what to change*, and only covered the
> marketing window rather than the full greenlight-to-release lifecycle. All six use free data
> only — no paid ticketing/booking APIs (BookMyShow, District, or similar) appear anywhere in
> this doc, and none of them touch post-release behavior, which stays the job of the existing
> MentionService/SentimentAlertService/MobilizeAlliesService monitoring stack.

## Repos in this workspace

| Repo | Role | Port |
|---|---|---|
| `AuraService` | User-facing Spring Boot backend (entities, auth, licensing, reports) | 8080 |
| `AuraMath` | Marketing-intelligence Spring Boot service (Hawkes/MOI scoring, sentiment, entity reports, Ask engine) | 8081 |
| `AuraDataFiller` | Batch/daemon Java app: CSV ingestion + crawlers (Sacnilk, Box Office Mojo, Koimoi, actor filmography, YouTube promo metrics, World Bank enrichment) | n/a (CLI/daemon) |
| `AuraSocialMediaService` | Raw social-post collector (X, YouTube, Reddit, Instagram) feeding AuraMath's tables | n/a |

**All four point at the same PostgreSQL database** (`jdbc:postgresql://localhost:5432/aura` —
confirmed in `AuraService/application.properties`, `AuraDataFiller/secrets.properties.template`,
`AuraMath/DataSourceConfig`, `AuraSocialMediaService/DatabaseService`). This is the single most
important fact for this build: **new cross-service data does not need a new API — a new table
in `aura` is instantly readable by every other service via a plain JPA/JDBC read.** Only use
the existing `AuraMathProxyService` HTTP-proxy pattern in AuraService when you need AuraMath's
*computation* (scoring/aggregation logic), not its raw tables.

## Facts these prompts assume

- `AuraDataFiller` already crawls and maintains, in the shared `aura` DB:
  - `movies_data_collection` — PK (`movie_name`,`release_date`,`language`); columns added
    dynamically per CSV (budget/collections/verdict etc. — confirm current column list with
    `\d movies_data_collection` before relying on a specific one).
  - `actors_data_collection` — PK (`actor_name`,`movie_name`,`release_date`); columns include
    `language, genre, director, rating, votes, runtime, role_position, character_name, awards,
    streaming_platform, status, sacnilk_url`.
  - `currency_rate_xe` — historical INR/USD conversion rates.
  - A YouTube promo-metrics table (populated by the `youtube.enabled` / `--youtube-scan`
    feature in `YoutubeDatabaseService`/`YoutubeEnrichmentService`) storing trailer/teaser/
    first-single publish dates, days-before-release, view/comment counts per movie. **Confirm
    its exact name and columns with Claude Code at the start of F5** — it wasn't pinned down
    during planning.
  - World Bank GDP/inflation enrichment (`WorldBankClient`, free, no auth) — confirm which
    table/columns it writes the resolved GDP/inflation values to; `EnrichmentService`
    orchestrates it but the persistence target needs confirming too.
- `AuraService`'s `ManagedEntity` has `name, type, owner, director, actors (List<String>),
  releaseDate, language, industry, genre, synopsis` — **no `musicDirector` field yet**.
- `AuraService` already has two near-identical LLM-scoring services
  (`ConflictBalanceServiceImpl`, `NarrativeNoveltyServiceImpl`) that fetch a synopsis, fill a
  prompt template from `application.properties`, call `LLMService.generateReply`, parse ordinal
  ratings, and affine-remap into a fixed `[floor, ceiling]` band. F1 replaces this pattern.
- `application.properties` has a **dead** `llm.prompt.generate.prediction` key used only by
  `MockAnalyticsService` (`GET /api/analytics/{movieId}`), which predicts box office from a
  hardcoded map of Tamil-industry "week of January/February" text blobs plus sentiment —
  nothing in it reads `movies_data_collection` or any real comps data. F9 replaces it.
- `AuraMath` already exposes entity-scoped intelligence (`EntityIntelService`,
  `EntityMarketingService`, `EntityReportController`) and a reactive spreader/ally lookup
  (`TopSpreadersController`, consumed by `AuraService`'s `TopSpreaderLookupService` /
  `MobilizeAlliesService`) — that lookup is **keyword/mention-driven**, i.e. it only finds
  people already talking about an entity. F7 builds a **proactive** directory queryable before
  any mentions exist.
- `AuraService` calls `AuraMath` today via `AuraMathProxyService.forwardGet(...)` (see
  `TopSpreaderLookupService` for the pattern: cache the result, hit
  `/api/marketing/top-50-spreaders/{keyword}`). New AuraService→AuraMath calls should follow
  this same proxy pattern.
- Mockito in this workspace cannot mock concrete classes on this JDK — mock interfaces only,
  in every repo.

---

## F1 — Config-driven factor scoring engine + persisted feature store

**Repo:** `AuraService`
**Covers:** Category 1 (Narrative & Screenplay), factors 1–15
**Depends on:** nothing

**Why first:** `ConflictBalanceServiceImpl`/`NarrativeNoveltyServiceImpl` are 95% identical
classes; scaling that pattern to the remaining 13 narrative factors means 13 more near-duplicate
services, 13 more prompt properties, 13 more LLM round-trips per entity. This collapses all 15
into one config table, one engine, and — since it batches by category — one LLM call per entity
instead of fifteen. It also gives every later feature (F8, F9, F10) a place to read/write scores.

**Design notes**
- New table `entity_factor_scores`: `entity_id, factor_key, score, sub_ratings (jsonb),
  rationale, source (enum: LLM_SYNOPSIS | LLM_SCRIPT | TALENT_STATS | CALENDAR | MANUAL),
  computed_at`. Unique on `(entity_id, factor_key)`, upserted on recompute.
- New `FactorDefinition` registry (plain Java, not DB-editable): id 1–15 from the catalog below,
  each with `key, direction, floor, ceiling, subRatingKeys[], subRatingWeights[], requiresScript
  (boolean)`.
- **Two of the 15 factors (4, 15) are premise-level and score fine from `synopsis` alone. The
  other 13 (1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14) are execution/structural properties —
  pacing, tonal shifts, dialogue quality, plot holes — that a 2-4 sentence synopsis cannot show.**
  Add a `screenplayText` (long text, nullable) field to `ManagedEntity` alongside `synopsis`. The
  engine must score `requiresScript=true` factors from `screenplayText` when present, and only
  fall back to `synopsis` for them with `source=LLM_SYNOPSIS` and the response explicitly labeled
  low-confidence (a `confidence` field alongside `score`) — never silently present a
  synopsis-only score for a structural factor as equivalent to a script-backed one.
- One `FactorScoringEngine.scoreCategory(entityId, category)` builds **one** prompt asking the
  LLM for all 15 factors' ordinal sub-ratings in a single JSON object (not 15 calls), using
  `screenplayText` when present and `synopsis` otherwise, applies the existing
  weighted-affine-remap formula generically per factor, and upserts into `entity_factor_scores`
  with the appropriate `source` and `confidence`.
- Migrate `ConflictBalanceServiceImpl`/`NarrativeNoveltyServiceImpl` logic into two
  `FactorDefinition` entries; keep `GET /api/analytics/{id}/conflict-balance` and
  `/narrative-novelty` working by having them read from `entity_factor_scores` (compute-on-miss),
  so no client contract breaks. Delete the two old service classes once parity is proven by tests.

### Prompt
```
In AuraService, replace the per-factor LLM-scoring pattern (ConflictBalanceServiceImpl,
NarrativeNoveltyServiceImpl are near-duplicate classes) with a config-driven scoring engine that
covers all 15 "Narrative Architecture and Screenplay Engineering" factors from a movie's synopsis
and, where the factor needs it, its full screenplay text.

Two of the 15 (Genre Template Adherence, Twist Effectiveness) are premise-level and score fine
from a short synopsis. The other 13 — including the two already-built factors — are
execution/structural properties (pacing, tonal shifts, dialogue, plot holes, ensemble cohesion,
etc.) that a 2-4 sentence synopsis cannot actually show. Don't silently score those from synopsis
as if it were equivalent to reading the script.

1. Add field screenplayText (String, nullable, long text) to ManagedEntity alongside the existing
   synopsis field. Update the managed_entities DDL doc in the README.

2. Add table entity_factor_scores: entity_id (FK managed_entities), factor_key (string),
   score (double), sub_ratings (jsonb), rationale (text), confidence (enum HIGH | LOW — LOW
   whenever a requiresScript factor had to fall back to synopsis-only scoring), source (enum
   LLM_SYNOPSIS | LLM_SCRIPT | TALENT_STATS | CALENDAR | MANUAL), computed_at (timestamp).
   Unique constraint on (entity_id, factor_key). Add the DDL to the README like other tables in
   this repo.

3. Add a FactorDefinition registry (a Java enum or sealed list of records, not DB-backed) with
   one entry per factor below: id, key, direction, floor, ceiling, requiresScript (boolean), list
   of sub-rating keys the LLM must return (1-5 ordinal), and the weights used to combine them into
   [floor, ceiling]. Use the existing ConflictBalanceServiceImpl (floor .25/ceiling .35, weights
   already coded, requiresScript=true) and NarrativeNoveltyServiceImpl (floor .30/ceiling .45,
   weights already coded, requiresScript=false — novelty is premise-level) as the first two
   entries verbatim. For the other 13 factors, design a reasonable small set of sub-ratings
   (2-4 each) and weights consistent with how those two are structured; tell me your sub-rating
   choices and requiresScript call for each so I can sanity-check them before finalizing.

   Factors 3-15: Screenplay Pacing and Rhythm (+/-25%, requiresScript), Genre Template Adherence
   vs Subversion (+/-20%, premise-level), Emotional Climax Payoff (+35-50%, requiresScript),
   Dialogue Punch/Catchphrases (+15-25%, requiresScript), Tonal Consistency across Halves
   (+/-30%, requiresScript), Subtext vs Preachiness (-15 to -25%, requiresScript), Logic
   Gaps/Plot Holes (-20 to -30%, requiresScript), Ensemble Cast Cohesion (+/-15%, requiresScript),
   Romantic Track Integration (+/-15%, requiresScript), Comedic Track Cohesion (+/-25%,
   requiresScript), Realism vs Melodrama (+/-20%, requiresScript), Flashback Relevance/Pacing
   (+/-15%, requiresScript), Twist Effectiveness (+20-30%, premise-level). Impact ranges are
   bounds on the score itself (same convention as the two existing factors), not just weight.

4. Add FactorScoringEngine.scoreCategory(entityId, category): builds ONE prompt (one new
   application.properties key, e.g. llm.prompt.generate.narrative.factors) asking the LLM for
   all 15 factors' sub-ratings + short rationale in a single JSON object. Use screenplayText as
   the source text for requiresScript factors when it's populated; when it's null, still score
   them from synopsis but mark confidence=LOW and source=LLM_SYNOPSIS (versus confidence=HIGH/
   source=LLM_SCRIPT when screenplayText was used). Premise-level factors always score at
   confidence=HIGH from whichever text is available. Calls LLMService once, parses the response,
   applies the generic weighted-affine-remap per FactorDefinition, and upserts each result into
   entity_factor_scores. One LLM call per entity instead of fifteen.

5. Update AnalyticsController's /conflict-balance and /narrative-novelty endpoints to read from
   entity_factor_scores (compute-via-engine on miss) instead of the two old services, preserving
   the current response shape. Once tests pass, delete ConflictBalanceServiceImpl,
   NarrativeNoveltyServiceImpl, and their now-unused DTOs/prompt properties.

6. Add GET /api/analytics/{movieId}/factors returning all persisted factor scores for an entity
   (id, key, score, confidence, rationale) — this is the read path F8/F9/F10 will build on, and
   confidence lets a caller distinguish real signal from a synopsis-only placeholder.

7. Tests: engine parses a multi-factor LLM JSON response correctly; each factor's remap respects
   its floor/ceiling; a requiresScript factor scored without screenplayText is persisted with
   confidence=LOW and source=LLM_SYNOPSIS; the two migrated endpoints return equivalent results to
   before the refactor; ownership is enforced the same way as other analytics endpoints. Mock
   interfaces only (Mockito can't mock concrete classes on this JDK).
```

---

## F1b — Manual factor entry for Production-House-only data

**Repo:** `AuraService`
**Covers:** the ~21 factors that the data audit found have no free/public source and never will
— factors 63–65, 67 (exam/election/sports/weather calendars), 71–80 (legal/censorship), and
82–87, 90 (private financial/deal terms)
**Depends on:** F1 (writes into the same `entity_factor_scores` table, `source=MANUAL`)

**Why this exists:** the data audit originally marked these factors `ABSENT`, implying a future
crawler would eventually fill them in. On review, most of them can't be — exam/election dates are
announced piecemeal with no stable API, and legal status and deal terms (MG splits, financing
rates, producer solvency) are private business facts no public source will ever expose. Building
crawlers for these would mean guessing or fabricating data. The honest fix is a direct entry
surface for the Production House, writing the exact same `entity_factor_scores` rows F1's LLM
engine writes, just with `source=MANUAL` and no LLM call involved.

**Design notes**
- No new scoring logic — this is a form, not a model. Each of the ~21 factors already has a
  `FactorDefinition` (floor/ceiling/direction) from F1's registry; MANUAL entry just lets a human
  supply the `score` (and optionally a free-text `rationale`) directly within that band.
- Some factors in this set are naturally boolean/categorical rather than a [floor, ceiling] score
  (e.g. "CBFC certificate obtained: yes/no", "election falls within ±2 weeks of release: yes/no").
  Where a factor is naturally boolean, map it to the FactorDefinition's floor/ceiling at entry
  time (yes → ceiling-side of its direction, no → floor-side) rather than inventing a separate
  scoring scheme just for this feature.
- Keep this to a plain CRUD surface (list the ~21 factors for an entity, let owner/admin fill in a
  score + optional note per factor) — no workflow, approval chain, or versioning beyond the
  existing `computed_at` upsert-on-recompute behavior F1 already has.

### Prompt
```
In AuraService, add a manual factor-entry path for the Production House, covering the subset of
factors that F1's FactorDefinition registry defines but that no crawler or LLM pass can honestly
fill in: factors 63-65, 67 (student exam schedules, elections, major sporting events, extreme
weather), 71-80 (legal/censorship: certification, bans, disputes, tax exemptions, etc.), and
82-87, 90 (minimum guarantee terms, territorial sales, financing interest rates, revenue share
splits, subtitle/dubbing quality, screen count allocation, producer debt/solvency). These write
into the same entity_factor_scores table F1 built, just with source=MANUAL and no LLM call.

1. Extend F1's FactorDefinition registry with entries for these ~21 factors (id, key, direction,
   floor, ceiling — no sub-rating keys/weights needed since there's no LLM prompt for these).
   Where a factor is naturally boolean/categorical rather than a continuous score (e.g. "CBFC
   certificate obtained"), document in the FactorDefinition how a yes/no answer maps to
   floor/ceiling for that factor's direction, rather than inventing a separate scoring scheme.

2. Add GET /api/entities/{id}/factors/manual listing all ~21 manual-only FactorDefinitions with
   whatever entity_factor_scores rows already exist for the entity (null if not yet entered).

3. Add PUT /api/entities/{id}/factors/manual/{factorKey} {score, rationale} upserting one row
   into entity_factor_scores with source=MANUAL, computed_at=now. Validate score falls within the
   factor's [floor, ceiling] band (accounting for direction). Enforce ownership via
   EntityAccessService like every other entity-scoped endpoint.

4. Tests: score outside the factor's floor/ceiling band is rejected; upsert overwrites a prior
   manual entry rather than duplicating; a boolean-mapped factor's yes/no round-trips to the
   correct floor/ceiling value; ownership enforced same as other endpoints. Mock interfaces only.
```

---

## F2 — Release calendar reference-data crawler

**Repo:** `AuraDataFiller`
**Covers:** Category 5 (Temporal), factor 61 (holidays/festivals) only
**Depends on:** nothing

> **Scope narrowed on review:** this originally also covered exams, elections, sports (63–65,
> 67) via a seeded CSV / cricket feed. The data audit reclassified those three as **MANUAL** —
> exam/election dates are announced too irregularly for a stable seed to stay current, and a free
> cricket-fixture feed reliable enough to trust wasn't confirmed. They're handled by
> **F1b** (Production House direct entry into `entity_factor_scores`) instead. F2 now only
> crawls holidays/festivals, which do have a solid free public source.

**Design notes**
- New table `release_calendar_events`: `event_date, event_type (HOLIDAY|FESTIVAL), region (state
  or 'NATIONAL'), title, severity (lift/penalty hint, -1.0 to +1.0), source_url, fetched_at`.
- Follow this repo's existing conventions exactly: a new package `calendar/` alongside
  `crawler/` and `enrichment/`, a `*.properties`-driven enable/disable + delay per source (mirror
  `crawler.request.delay.ms`, `RobotsTxtPolicy` usage), and a `CalendarDatabaseService` modeled
  on `CrawlerDatabaseService`.
- Sources, both free: national holidays via date.nager.at's public JSON API (no auth); major
  Indian festivals (Pongal, Diwali, Eid, Onam, Ugadi) — either the same API's `India` calendar or
  a small hand-seeded table of computed festival dates per year (these follow lunar/solar rules
  that are easier to seed than crawl reliably — say so explicitly rather than guessing a scraper
  will hold up).

### Prompt
```
In AuraDataFiller, add a release-calendar reference-data source for holidays/festivals only (this
originally also targeted exams, elections, and sports fixtures, but those are now handled by a
Production-House manual-entry feature in AuraService instead — see F1b in this doc — since no
reliable free source exists for them). Add a new package `calendar/` alongside the existing
`crawler/` and `enrichment/` packages, following this repo's conventions (properties-driven
enable/disable per source, RobotsTxtPolicy where a site is actually scraped, a *DatabaseService
class modeled on CrawlerDatabaseService).

1. Create table release_calendar_events in the shared `aura` DB: event_date (date), event_type
   (HOLIDAY|FESTIVAL), region (state name or 'NATIONAL'), title (text), severity (numeric, a -1.0
   to +1.0 lift/penalty hint), source_url, fetched_at. Idempotent upsert on
   (event_date, event_type, region, title).

2. National holidays + major festivals: pull from date.nager.at's free public holiday API
   (https://date.nager.at/api/v3/PublicHolidays/{year}/IN, no auth) for HOLIDAY rows. For
   FESTIVAL rows not covered there (Pongal, Diwali, Eid, Onam, Ugadi etc.), tell me whether you
   found a reliable free source before writing a scraper — if not, add a small hand-seeded
   festival-date table (2024-2027) as a documented starting point rather than guessing.

3. Wire this behind its own enabled/interval properties in application.properties, matching the
   naming convention of crawler.* properties already there (e.g. calendar.holidays.enabled,
   calendar.holidays.interval.hours).

4. Tests: upsert is idempotent; date.nager.at parsing handles a sample response; hand-seeded
   festival table loading (if added) doesn't duplicate rows on restart.
```

---

## F3 — Competing/planned release calendar crawler

**Repo:** `AuraDataFiller`
**Covers:** Category 5, factor 62 (direct box-office clashes)
**Depends on:** nothing (can build in parallel with F2)

**Design notes**
- New table `planned_releases`: `movie_name, industry, language, planned_release_date,
  confidence (CONFIRMED|RUMORED), source_url, fetched_at`.
- Source: Wikipedia "List of Kannada/Tamil/Telugu/Malayalam/Hindi films of {year}" pages — public,
  structured tables, no auth, permissive robots.txt for Wikipedia. One parser per year-list page
  style (they're fairly uniform across industries) rather than one per industry.
- Reuse the HTML-table-parsing shape already established by `SacnilkHtmlParser`/`KoimoiParser`
  and the `RobotsTxtPolicy` check already used by the actor crawlers.

### Prompt
```
In AuraDataFiller, add a crawler for planned/announced film releases, so we can detect box-office
date clashes ahead of time. Follow the existing crawler/ package conventions (RobotsTxtPolicy,
properties-driven enable + delay, a *Parser + *CrawlerService pair like SacnilkCrawlerService/
SacnilkHtmlParser).

1. Create table planned_releases: movie_name, industry, language, planned_release_date
   (nullable — many entries are "TBA" or year-only), confidence (CONFIRMED|RUMORED), source_url,
   fetched_at. Upsert on (movie_name, industry) with newer fetched_at winning on conflict.

2. Crawl Wikipedia's "List of Kannada films of {year}", "List of Tamil films of {year}", "List of
   Telugu films of {year}", "List of Malayalam films of {year}" pages (check robots.txt first,
   same as the existing crawlers do) for the current and next year, for each of the four
   industries. Parse the release-date column into planned_release_date; rows with no firm date
   go in as confidence=RUMORED with planned_release_date null.

3. Add a scheduled daemon mode consistent with the other crawlers (crawler.plannedreleases.enabled,
   .interval.hours, .delay.ms), re-running periodically since dates get confirmed/shifted over time.

4. Tests: table parsing against a saved sample Wikipedia page fixture per industry; upsert
   behavior when a RUMORED row later gets a confirmed date.
```

---

## F4 — Release-date optimizer

**Repo:** `AuraService`
**Covers:** Category 5, factors 61, 62 automatically; 63–65, 67 only if the entity has manual
scores from **F1b** for the window in question (score a candidate release date)
**Depends on:** F2, F3, F1b (for the manual-entry factors, optional at call time)

> **Adjusted on review:** `release_calendar_events` no longer carries EXAM/ELECTION/SPORTS/
> WEATHER rows (see F2's narrowed scope) — those are per-entity manual factor scores from F1b,
> not a shared date-indexed calendar table, since they have no reliable source to crawl into one.
> The optimizer still applies them, just by reading the entity's own `entity_factor_scores` rows
> for factors 63/64/65/67 (if the Production House has entered any) as a flat penalty/lift rather
> than a date-range lookup — it has no way to know *which* future dates an unscored exam/election/
> weather window falls on without that manual input existing first.

**Design notes**
- New read-only JPA entities/repositories in AuraService pointing at `release_calendar_events`
  and `planned_releases` (same `aura` DB — no HTTP call needed, this is the payoff of the shared
  database).
- Deterministic scoring, no LLM: for each candidate date in a window, sum holiday/festival lift,
  subtract clash penalty (weighted by how many `planned_releases` fall within ±3 days, scaled up
  when `industry`/`language` matches the entity's own). If the entity has manual scores for
  factors 63/64/65/67 (from F1b), fold them in as a flat adjustment and say so in the rationale;
  if not, state plainly that those factors weren't considered rather than pretending a neutral
  score means "no risk."
- New endpoint on `EntityController` or a new `ReleaseOptimizerController`.

### Prompt
```
In AuraService, add a release-date optimizer that scores candidate release dates for a managed
entity using the release_calendar_events and planned_releases tables that AuraDataFiller
maintains in the same `aura` database, plus the entity's own manually-entered factor scores for
exam/election/sports/weather (F1b) where present.

1. Add read-only JPA entities + repositories for release_calendar_events and planned_releases
   (map only the columns AuraService needs to read; these tables are owned/written by
   AuraDataFiller, AuraService only queries them).

2. Add ReleaseOptimizerService.scoreWindow(entityId, windowStartDate, windowEndDate): for each
   date in the window, compute a deterministic score:
   - + lift from any release_calendar_events HOLIDAY/FESTIVAL row within +/-3 days (severity field)
   - - clash penalty from planned_releases rows within +/-3 days, weighted higher when
     industry/language matches the entity's own industry/language
   - if the entity has entity_factor_scores rows (source=MANUAL) for factors 63/64/65/67, fold
     each in as a flat adjustment applied to every date in the window (not date-range-matched,
     since there's no calendar table for these); if none exist, don't apply any adjustment and
     say so explicitly in the response rather than implying a neutral score means low risk.
   Return the top 10 dates ranked by score, each with a plain-text rationale built from which
   events contributed (e.g. "Pongal week (+0.6), no same-industry clash, no exam/election data
   entered for this entity").

3. Add GET /api/entities/{id}/release-optimizer?windowDays=60 (default window: today+30 to
   today+90) returning the ranked list. Enforce entity ownership via EntityAccessService like
   every other entity-scoped endpoint in this controller layer.

4. Tests: a date inside a festival window scores higher than a neutral date; a date near a
   same-industry planned release scores lower than near a different-industry one; an entity with
   manual exam/election/sports/weather scores gets the flat adjustment applied to every date in
   the window, and the response is explicit when no such manual scores exist. Mock interfaces
   only.
```

---

## F5 — Promo timeline recommender

**Repo:** `AuraMath`
**Covers:** Category 4, factors 46, 47 (timing, not quality, of teaser/trailer/first single)
**Depends on:** AuraDataFiller's existing YouTube promo-metrics table (already built — confirm
its name/columns as step 1 of the prompt below)

**Design notes**
- `AuraDataFiller`'s `youtube.enabled` feature already searches YouTube for each movie's
  official trailer, teaser, and first single, storing publish date, days-before-release, view
  and comment counts. That table is this feature's entire training set — no new crawling needed.
- New AuraMath service aggregates those offsets grouped by industry/genre (join to
  `movies_data_collection`), computing median/IQR days-before-release for each asset type, and
  correlates against `movies_data_collection` opening-weekend figures to flag which offset
  quartile associates with stronger openings.
- Expose as a new endpoint under `/api/marketing/promo-timeline`, mirroring the existing
  `EntityReportController`/`GenreMarketingAPI` controller style (`@Autowired JdbcTemplate`, no JPA
  in this repo).

### Prompt
```
In AuraMath, add a promo-timeline recommender that tells a caller, for a given industry/genre,
when to release the teaser, trailer, and first single relative to a target release date.

First, find and tell me the exact table/columns AuraDataFiller's YouTube promo-metrics feature
(YoutubeDatabaseService / YoutubeEnrichmentService, `youtube.enabled` in that repo) writes to in
the shared `aura` database — the table name wasn't pinned down during planning. Confirm it has,
per movie: asset type (trailer/teaser/song), publish date, days-before-release, view count.

Then:
1. Add PromoTimelineService (JdbcTemplate-based, like EntityMarketingService in this same repo):
   group that YouTube promo table's rows by industry/genre (join movies_data_collection on
   movie_name/release_date/language for industry+genre), and for each (industry, genre, asset
   type) compute median and IQR of days-before-release, plus median opening-weekend collection
   (from movies_data_collection) split by whether the asset's offset was above/below the median —
   to surface which timing quartile associates with stronger openings.

2. Add PromoTimelineController: GET /api/marketing/promo-timeline?industry=X&genre=Y returning,
   per asset type, the recommended days-before-release (median + IQR) and a one-line note on
   which timing band historically opened stronger, when the sample size is large enough to say
   so (state the sample size; don't imply confidence you don't have on a handful of movies).

3. Handle the low-sample case explicitly: if fewer than N (choose a sensible threshold, e.g. 8)
   comparable movies exist for that industry/genre pair, fall back to the industry-wide numbers
   (ignore genre) and say so in the response, rather than returning a noisy tiny-sample estimate.

4. Tests: median/IQR computed correctly on a fixed fixture; low-sample fallback triggers at the
   right threshold; industry/genre filter joins correctly against movies_data_collection.
```

---

## F6 — Creator / influencer directory crawler

**Repo:** `AuraDataFiller`
**Covers:** feeds Category 4, factor 53 (influencer-driven promotion) and the user's "who do we
reach out to per industry" ask
**Depends on:** nothing

**Design notes**
- New table `creator_directory`: `channel_id, platform, display_name, industry, language,
  subscriber_count, avg_recent_views, category_tags, last_refreshed`.
- Reuse the existing `YoutubeApiClient` (already wired for API-key auth + quota-aware calls in
  `YoutubeEnrichmentService`) for `search.list`/`channels.list` by language + film-industry
  keywords ("Kollywood", "Tollywood", "Sandalwood", "Mollywood" + regional entertainment-news
  channel searches).
- Seed the search keyword list from a small curated file rather than inventing channel names —
  same "don't fake a crawler" principle as F2's exam/election data.

### Prompt
```
In AuraDataFiller, add a proactive creator/influencer directory, distinct from anything reactive
— this must be queryable for an industry/language BEFORE any social mentions of a specific movie
exist, unlike AuraMath's existing keyword-driven top-spreaders lookup.

1. Create table creator_directory: channel_id, platform (default 'YOUTUBE' for this pass),
   display_name, industry, language, subscriber_count, avg_recent_views, category_tags (text[]),
   last_refreshed. Upsert on (channel_id, platform).

2. Add a CreatorDirectoryCrawlerService reusing the existing YoutubeApiClient (see
   YoutubeEnrichmentService for the quota-aware call pattern already established) to run
   search.list against a curated keyword seed list per industry (e.g. "Kollywood news",
   "Tollywood promotions", "Sandalwood movie updates", "Mollywood entertainment") — put the seed
   list in a properties/resource file, not hardcoded, so it's easy to extend. For each result
   channel, fetch subscriber_count and recent-video average views via channels.list, and tag
   industry/language from the search bucket it came from.

3. Wire it behind its own properties (creator.directory.enabled, .interval.hours,
   .daily.quota.units) following the youtube.* naming convention already in
   application.properties, and share the daily quota budget sensibly with the existing
   youtube.enabled promo-metrics scan (both hit the same API key/quota) — document how the two
   divide the daily 10,000-unit budget.

4. Tests: upsert dedupes by (channel_id, platform); quota budgeting stops the crawl before
   exceeding the configured unit cap.
```

---

## F7 — Influencer recommendation endpoint

**Repo:** `AuraMath` (query logic) + `AuraService` (proxy + entity-scoped endpoint)
**Covers:** Category 4, factor 53
**Depends on:** F6

**Design notes**
- AuraMath: new controller querying `creator_directory` filtered by industry/language, ranked by
  `subscriber_count`/`avg_recent_views`, mirroring `TopSpreadersController`'s response shape so
  AuraService's proxy layer stays consistent.
- AuraService: new proxy method on `AuraMathProxyService` (same pattern `TopSpreaderLookupService`
  already uses) + a new entity-scoped endpoint that resolves the entity's industry/language and
  calls it.

### Prompt
```
Add a proactive influencer-recommendation path: AuraMath serves the query, AuraService exposes it
per-entity. This is separate from the existing reactive top-spreaders/mobilize-allies path
(which only finds people already discussing a specific entity's mentions) — it must work for a
movie with zero mentions yet.

In AuraMath:
1. Add CreatorRecommendationController: GET /api/marketing/creator-directory?industry=X&
   language=Y&limit=20 querying the creator_directory table (built in AuraDataFiller, same `aura`
   DB), ranked by a simple reach score (e.g. log(subscriber_count) + log(avg_recent_views)).
   Match the response shape style of TopSpreadersController for consistency.

In AuraService (build after the above, or in parallel and wire last):
2. Add a proxy method to AuraMathProxyService (same pattern as TopSpreaderLookupService's
   getSpreaderProfiles: forwardGet + TtlCache) hitting the new /api/marketing/creator-directory
   endpoint.
3. Add GET /api/entities/{id}/promo-creators: resolves the entity's industry + language, calls
   the proxy, returns the ranked creator list. Enforce ownership via EntityAccessService like
   other entity-scoped endpoints.

Tests (both repos): AuraMath ranking is stable/deterministic for a fixed fixture; AuraService's
proxy caches like the existing pattern and enforces ownership before returning cached results
(same ownership-before-cache rule used elsewhere in this controller layer). Mock interfaces only.
```

---

## F8 — Cast/crew historical feature ingestion

**Repo:** `AuraService`
**Covers:** Category 2, factors 20 (directorial brand equity), 23 (star satiation) — the subset
that's honestly computable from data already collected; flags the rest as a data gap rather than
faking it
**Depends on:** F1 (writes into `entity_factor_scores`)

**Design notes**
- `AuraDataFiller`'s `actors_data_collection` table has `actor_name, movie_name, release_date,
  director, rating, votes` — enough to compute, purely arithmetically (no LLM):
  - **Star satiation (23):** count of that actor's movies with `release_date` in the trailing 12
    months from the entity's own `release_date`.
  - **Directorial brand equity (20):** average `rating`/`votes`-weighted score across the
    director's past movies in the same table.
- `ManagedEntity` has no `musicDirector` field — add it now, since the user explicitly asked for
  music-director signal, even though `actors_data_collection` has no equivalent table to join
  against yet (there's no music-director crawler in this workspace today — say so plainly rather
  than inventing fake data for it).
- New read-only JPA entity/repository for `actors_data_collection` (same `aura` DB, owned/written
  by AuraDataFiller).
- Name matching: exact case-insensitive match first; note that a fuzzy fallback (like the
  Levenshtein-ratio matching AuraDataFiller's own actor crawler already uses) is a reasonable
  follow-up but don't over-build it in this pass — log unmatched names instead of guessing.

### Prompt
```
In AuraService, add a field musicDirector to ManagedEntity (String, nullable — mirrors the
existing `director` field) and update the managed_entities DDL doc in the README.

Then add cast/crew historical feature ingestion using AuraDataFiller's actors_data_collection
table (same `aura` DB — actor_name, movie_name, release_date, director, rating, votes columns):

1. Add a read-only JPA entity + repository for actors_data_collection.

2. Add TalentFeatureService.computeCastFeatures(entityId): for each actor in the entity's
   `actors` list, exact case-insensitive match against actors_data_collection.actor_name; count
   their movies with release_date in the 12 months before this entity's own releaseDate — write
   that as factor "starSatiation" (direction Negative, catalog impact -15% to -25%; higher count
   = more negative) into entity_factor_scores with source=TALENT_STATS. For the entity's
   director, average the rating (votes-weighted) across their past movies in the same table;
   write as factor "directorialBrandEquity" (Positive, +25% to +40%) with source=TALENT_STATS.
   Both computations are pure arithmetic — no LLM call.

3. Log (don't silently drop) any actor/director name with no match in actors_data_collection —
   exact-match only in this pass, no fuzzy matching yet.

4. Do NOT attempt to compute a music-director factor — there is no music-director data source
   anywhere in this workspace yet. Leave the new musicDirector field populated only from user
   input for now, and tell me plainly in your summary that this factor has no data source, rather
   than fabricating a score for it.

5. Add GET /api/entities/{id}/cast-features triggering computeCastFeatures and returning the
   written scores plus the list of unmatched names.

6. Tests: satiation count is correct for a fixed 12-month window; brand-equity averaging is
   votes-weighted correctly; unmatched names are reported, not silently scored as zero. Mock
   interfaces only.
```

---

## F9 — Comps-based box office prediction

**Repo:** `AuraMath` (model), `AuraService` (replaces the existing mock endpoint)
**Covers:** the core ask — predicted revenue for a future movie
**Depends on:** F1 (factor scores), F8 (talent features)

**Design notes**
- Retires `MockAnalyticsService` / the unused `llm.prompt.generate.prediction` hardcoded-Tamil-
  January-weeks approach — that prompt asks an LLM to eyeball one anecdote and invent numbers; it
  doesn't touch `movies_data_collection` at all.
- Model: **comps-based multiplicative baseline**, not a black-box regression — appropriate given
  the still-growing dataset size. Baseline = average opening/total gross of nearest-neighbor
  comps in `movies_data_collection` (same industry, same/adjacent genre, similar budget band if
  available) × Π(1 + factor_score) using every row currently in `entity_factor_scores` for the
  entity (F1's narrative scores + F8's talent scores) against the catalog's documented impact
  direction per factor.
- This lives in AuraMath because it's a computation over AuraMath's own comps table
  (`movies_data_collection`) joined against AuraService's `entity_factor_scores` (same DB, plain
  cross-table SQL — no service call needed for the join itself).
- AuraService's `GET /api/analytics/{movieId}` gets re-pointed at this via the existing
  `AuraMathProxyService` pattern.

### Prompt
```
In AuraMath, add a comps-based box office prediction model to replace AuraService's current
MockAnalyticsService, which predicts from a hardcoded map of Tamil-industry week-by-week text
blobs and never touches real comps data. Do NOT build a black-box regression — the dataset is
still growing; use a transparent, auditable comps-and-multipliers approach.

1. Add BoxOfficePredictionService (JdbcTemplate-based): given an entityId, industry, genre,
   read-only join against AuraService's entity_factor_scores table (same `aura` DB — confirm its
   columns match what F1 in AuraService produced: entity_id, factor_key, score) to get every
   scored factor for that entity.

2. Compute a baseline from movies_data_collection: average opening-day and lifetime-gross figures
   across the nearest comps — same industry, same or adjacent genre, and (if a budget column is
   available in movies_data_collection — confirm this first) similar budget band. Fall back to
   industry-wide averages if fewer than a sensible sample-size threshold (state your chosen
   threshold) of comps match on genre too, same low-sample honesty as the promo-timeline feature.

3. Apply each entity_factor_scores row as a multiplier on the baseline: (1 + score) where score
   is already signed per the catalog's Positive/Negative/Bidirectional convention used when F1
   wrote it. Multiply them together (not summed) across all available factors; entities with
   fewer scored factors just get fewer multipliers, not a penalty.

4. Add BoxOfficePredictionController: GET /api/marketing/box-office-prediction/{entityId}
   returning: baseline range, list of applied factors with their individual multiplier and a
   plain-text label, and the final predicted range (opening + lifetime). Include the comp sample
   size used so a caller can judge confidence.

5. Tests: multiplier composition is order-independent and correct; low-sample fallback to
   industry-wide comps triggers correctly; a factor with no entity_factor_scores row is skipped,
   not treated as neutral 1.0 in a way that silently masks missing data (surface it as "N/15
   factors scored" in the response).

Separately, in AuraService:
6. Add a proxy method to AuraMathProxyService for GET /api/marketing/box-office-prediction/
   {entityId}, following the existing forwardGet + TtlCache pattern.
7. Re-point AnalyticsController's GET /api/analytics/{movieId} at this proxy instead of
   MockAnalyticsService. Delete MockAnalyticsService, the AnalyticsService interface if it has no
   other implementation, and the dead llm.prompt.generate.prediction property once this is
   verified working end-to-end.
8. Tests: the endpoint returns AuraMath's response shape; ownership enforcement is unchanged.
   Mock interfaces only.
```

---

## F10 — Unified movie launch plan report

**Repo:** `AuraService`
**Covers:** ties F1, F4, F5, F7, F9 into one deliverable
**Depends on:** F1, F4, F5, F7, F9

**Design notes**
- Reuse the existing `EntityMarketingReportService`/`EntityMarketingReportPdfService` pattern
  (already produces a shareable report + PDF for an entity) rather than inventing a new report
  pipeline.
- One orchestration call per section; each section degrades gracefully (shows "not yet available"
  rather than erroring) if an upstream feature hasn't been run for that entity yet — since a user
  may paste this prompt before some earlier features are live for older entities.

### Prompt
```
In AuraService, add a unified "Movie Launch Plan" report that combines the predictive features
built in this workspace, reusing the existing EntityMarketingReportService/
EntityMarketingReportPdfService pattern (don't build a new report pipeline from scratch).

1. Add LaunchPlanService.buildPlan(entityId) assembling:
   - narrativeFactors: from GET-equivalent of /api/analytics/{id}/factors (F1)
   - releaseDateRecommendation: top 3 dates from ReleaseOptimizerService (F4)
   - promoTimeline: from the AuraMath promo-timeline proxy (F5)
   - promoCreators: from /api/entities/{id}/promo-creators (F7)
   - boxOfficePrediction: from the AuraMath prediction proxy (F9)
   Each section must degrade to a clear "not computed yet — call {endpoint} first" placeholder
   instead of throwing, if that upstream data doesn't exist for this entity yet.

2. Add GET /api/entities/{id}/launch-plan returning the assembled JSON, and
   GET /api/entities/{id}/launch-plan/pdf rendering it via the existing PDF pattern
   (EntityMarketingReportPdfService), same OpenPDF approach already used in this repo.

3. Enforce entity ownership via EntityAccessService like every other entity-scoped endpoint.

4. Tests: a fully-populated entity produces all five sections; an entity missing some upstream
   data gets clear placeholders, not a 500. Mock interfaces only.
```

---

## F11 — Revenue lever simulator ("what should we change")

**Repo:** `AuraMath` (extends F9), proxied via `AuraService`
**Covers:** turns F1/F8's diagnostic scores into a prescriptive ranking — the "what needs to
change" half of the question this doc opened with
**Depends on:** F9

**Design notes**
- F9's model is already `baseline × Π(1 + score)` over `entity_factor_scores`. A lever simulator
  needs no new data — just two more entry points on the same service: (a) substitute one factor's
  score and recompute, (b) automatically rank every currently-scored factor by how much moving it
  to its catalog-defined ceiling (or floor, for Negative-direction factors) would move the number.
- (b) is the direct answer to "what should we change" — it only ranks factors that actually have
  a row in `entity_factor_scores` for this entity; it must not invent deltas for factors nobody's
  scored yet.

### Prompt
```
In AuraMath, extend the BoxOfficePredictionService/BoxOfficePredictionController built in F9 with
a "what changes the number" lever simulator — this directly answers the production house question
"what should we change to raise revenue," which the raw prediction alone doesn't.

1. Add BoxOfficePredictionService.simulateWithOverride(entityId, factorKey, proposedScore):
   re-run the exact same baseline × Π(1 + score) computation from F9, but substitute proposedScore
   for that one factor_key's value (don't mutate entity_factor_scores — this is a hypothetical,
   read-only run). Return the simulated prediction alongside the real one and the delta.

2. Add BoxOfficePredictionService.rankLevers(entityId): for every factor currently present in
   entity_factor_scores for this entity, compute the delta between (a) the real prediction and
   (b) the prediction if that one factor were moved to its FactorDefinition's catalog ceiling (or
   floor, if the factor's direction is Negative, since "improved" means smaller for those). Sort
   descending by delta. Only use factors that actually have a row in entity_factor_scores for
   this entity — don't invent deltas for unscored factors.

3. Add two endpoints on BoxOfficePredictionController:
   - GET /api/marketing/box-office-prediction/{entityId}/levers -> ranked list from rankLevers,
     each entry: factorKey, currentScore, targetScore, predictedDelta (absolute and %).
   - POST /api/marketing/box-office-prediction/{entityId}/what-if {factorKey, proposedScore} ->
     result of simulateWithOverride.

4. In AuraService, add matching proxy methods on AuraMathProxyService (same forwardGet pattern as
   F9's proxy) and GET /api/entities/{id}/revenue-levers + POST /api/entities/{id}/revenue-levers/
   what-if, enforcing ownership via EntityAccessService like other entity-scoped endpoints.

5. Tests: ranking never includes a factor absent from entity_factor_scores; a Positive-direction
   factor's "improved" simulation moves toward the ceiling, a Negative-direction factor's moves
   toward the floor; what-if never persists the override. Mock interfaces only.
```

---

## F12 — Casting-alternative recommender

**Repo:** `AuraService`
**Covers:** "whom to cast" — extends Category 2 from a diagnostic score (F8) to an actual
recommendation
**Depends on:** F8, F3 (may need to extend `planned_releases` — see below)

**Design notes**
- Reuses F8's read-only `actors_data_collection` repository: rank candidate actors (in the
  entity's genre/industry, excluding actors already attached) by a votes-weighted rating average
  (same formula F8 uses for `directorialBrandEquity`), penalized by their trailing-12-months-
  from-today release count (satiation, same formula as F8 but dated from today, not the target
  entity's release date — this is a casting-time decision).
- Overcommitment risk needs a cast list on `planned_releases`, which F3 didn't capture (it only
  stores movie/industry/date). Wikipedia's "List of X films of YYYY" tables usually have a Cast
  column, so this prompt asks Claude Code to extend F3's parser and table with it, rather than
  assuming it's already there.

### Prompt
```
In AuraService, add a casting-alternative recommender using the actors_data_collection data F8
already reads, plus F3's planned_releases table for a scheduling-risk signal.

1. First check whether F3's planned_releases table captures a cast list. It likely only has
   movie_name/industry/language/planned_release_date/confidence/source_url today. If so, this is
   a small extension to AuraDataFiller's F3 crawler, not just AuraService: add a cast_names
   text[] column to planned_releases and update the Wikipedia "List of X films of YYYY" table
   parser to capture the Cast column those pages typically have (check a live page first — if a
   given industry's list page has no Cast column, leave cast_names null for those rows rather
   than guessing). Do this in the AuraDataFiller repo as a prerequisite step, then come back to
   AuraService for the rest.

2. Add TalentFeatureService.recommendAlternates(entityId, roleDescription, limit): query
   actors_data_collection for actors who've appeared in movies matching the entity's genre and/or
   industry, excluding actors already in the entity's `actors` list. For each candidate compute:
   - brandScore: votes-weighted average rating across their filmography in that genre (same
     formula F8 uses for directorialBrandEquity, applied to actors)
   - currentSatiation: count of their movies released in the trailing 12 months from TODAY
   - pipelineRisk: count of planned_releases rows (post step 1) whose cast_names contains this
     actor's name and whose planned_release_date is still in the future — a coarse overcommitment
     signal. If cast_names came back mostly null from step 1, say so in the response rather than
     silently reporting pipelineRisk=0 as if it means "no other projects."
   Rank by brandScore descending, currentSatiation ascending.

3. Add GET /api/entities/{id}/casting-alternatives?roleDescription=X&limit=10 returning ranked
   candidates with all three component scores visible (not just a blended number), so a producer
   can see why each was suggested. Enforce ownership via EntityAccessService.

4. Tests: excludes actors already attached to the entity; satiation window is trailing-12-months-
   from-today; pipelineRisk is honest about null cast_names rather than treating it as zero risk;
   ranking is stable for a fixed fixture. Mock interfaces only.
```

---

## F13 — Production-stage lifecycle tracking

**Repo:** `AuraService`
**Covers:** makes "track the movie from greenlight, not just from the marketing window" a real,
queryable dimension — the practical prerequisite for F14
**Depends on:** nothing

**Design notes**
- `MentionService`/the sentiment stack only becomes useful for an entity once it exists in
  `managed_entities` — but nothing currently distinguishes *when in the production timeline* a
  given mention landed. Adding a stage dimension lets a report compare "reaction to the casting
  announcement" against "reaction to the trailer" without a new report pipeline.
- Also the trigger point F14 needs: talent risk-tracking should start the moment a cast is
  announced, not the moment a trailer drops.

### Prompt
```
In AuraService, add first-class production-stage tracking to ManagedEntity, so mention/sentiment
data collected from the moment a project is greenlit (not just from the marketing window) becomes
usable and comparable across stages.

1. Add enum ProductionStage { CONCEPT, CASTING_ANNOUNCED, IN_PRODUCTION, POST_PRODUCTION,
   MARKETING, RELEASED }. Add productionStage (default CONCEPT) to ManagedEntity. Update the
   managed_entities DDL doc in the README.

2. Add table entity_stage_history: entity_id, stage, changed_at, changed_by (user id). On every
   stage change, append a row — never overwrite; this is an audit trail a production house can
   look back on.

3. Add PATCH /api/entities/{id}/stage {stage} (owner or admin, via EntityAccessService) updating
   ManagedEntity.productionStage and appending to entity_stage_history. Reject backward
   transitions (e.g. RELEASED -> CONCEPT) with a clear 4xx unless the caller is admin.

4. Add an optional `stage` query param to the existing mention-listing and dashboard endpoints
   (check MentionController and DashboardController for the right insertion points) that, when
   present, filters results to mentions whose postDate falls within that stage's time window
   (derived from entity_stage_history) — so a production house can compare reaction across stages
   without a new report pipeline.

5. Tests: stage history is append-only; backward transitions rejected for non-admins;
   stage-filtered mention query correctly bounds by the stage's changed_at window. Mock interfaces
   only.
```

---

## F14 — Continuous talent sentiment-risk radar

**Repo:** `AuraMath` (computation, reusing the existing sentiment pipeline) + `AuraService`
(watchlist + surfacing)
**Covers:** Category 2 factors 22 (off-screen controversy), 24 (off-script event speech) —
tracked per-talent, independent of any one film
**Depends on:** F13 (the CASTING_ANNOUNCED trigger)

**Design notes**
- `AuraMath`'s `EntityMarketingService` already matches a keyword against
  `x_posts`/`youtube_comments`/`reddit_posts`/`instagram_posts` and aggregates sentiment per
  author for an *entity's* keywords. This feature reuses the exact same matching approach, keyed
  by a talent's name instead — the only real change is what's matched and that it's tracked as a
  rolling trend, not a one-off aggregate.
- `AuraService` owns *when* to start watching someone (at CASTING_ANNOUNCED, from F13); `AuraMath`
  owns *how* their sentiment is computed, matching the existing split of responsibilities between
  the two services elsewhere in this doc.

### Prompt
```
In AuraService and AuraMath, add a talent sentiment-risk radar that tracks an actor/director's
public sentiment continuously, independent of any single movie, so a new project inherits risk
context on day one of casting instead of discovering it after the fact.

In AuraService:
1. Add table talent_watchlist: talent_name, category (ACTOR|DIRECTOR), first_watched_at,
   source_entity_id (nullable FK, the entity that first triggered watching this person).
   Deduplicate on (talent_name, category) case-insensitively.
2. Hook into the PATCH /api/entities/{id}/stage endpoint from F13: the first time an entity's
   stage reaches CASTING_ANNOUNCED, insert talent_watchlist rows for its director and every actor
   in its `actors` list (skip ones already present).
3. Add a proxy method on AuraMathProxyService + GET /api/entities/{id}/talent-risk: for each of
   the entity's attached talent, calls AuraMath's new endpoint (below) and returns their current
   sentiment trend plus a spike flag.

In AuraMath:
4. Add TalentSentimentService, modeled directly on EntityMarketingService's existing
   keyword-matching pattern (matching a keyword against x_posts/youtube_comments/reddit_posts/
   instagram_posts and aggregating sentiment per author) — but match posts against a talent's
   name as the keyword instead of entity_keywords rows, and compute a rolling weekly sentiment
   average over the trailing 12 weeks rather than a single aggregate.
5. Add TalentSentimentController: GET /api/marketing/talent-sentiment/{talentName} returning the
   weekly trend and a spike flag (e.g. any week's negative-sentiment share more than 2 standard
   deviations above that talent's own trailing baseline — reuse whatever statistical-outlier
   approach is simplest given what's already in this codebase; don't add a new ML dependency for
   this).

6. Tests (both repos): watchlist dedupes correctly and only triggers at CASTING_ANNOUNCED, not
   every stage change; talent sentiment trend computation matches a fixed fixture; spike detection
   flags a known outlier week and not a normal one. Mock interfaces only.
```

---

## F15 — Pre-release momentum crawler (search trends + trade-press predictions)

**Repo:** `AuraDataFiller`
**Covers:** a leading-indicator buzz signal distinct from the reactive social-mention stack
(which only sees what's already been posted about a *specific* entity)
**Depends on:** nothing

**Design notes**
- Two free, public sub-sources, same table, same crawler-package conventions already used for
  Sacnilk/Koimoi/Box Office Mojo (properties-driven enable + delay, `RobotsTxtPolicy` before
  scraping any page).
- Google Trends has no official API. Scraping its public page is the same "public page, no auth"
  pattern the existing crawlers already use elsewhere — flag it plainly as unofficial and subject
  to breakage if the page changes, the same maintenance risk profile the existing HTML-parsing
  crawlers already carry, nothing new.
- Trade-press predictions (Sacnilk/Box Office India/Filmfare pre-release "opening day prediction"
  articles) reuse `SacnilkHtmlParser`'s approach against a different page type than the
  box-office-result pages `SacnilkCrawlerService` already parses.

### Prompt
```
In AuraDataFiller, add a pre-release momentum crawler capturing two free, public leading-indicator
signals distinct from anything in movies_data_collection or actors_data_collection today: search
interest and trade-press pre-release predictions.

1. Create table prerelease_momentum_signals: movie_name, signal_type (SEARCH_TRENDS|
   TRADE_PREDICTION), captured_at, value_numeric (nullable — trend index or predicted opening
   figure), value_text (nullable — free-text prediction snippet), source_url. Append-only — don't
   upsert over time; the trend across multiple captures IS the signal.

2. Search-trends sub-source: trends.google.com has no official API. Scrape its public page the
   same way this repo already treats Sacnilk/Koimoi/Box Office Mojo — check robots.txt first via
   the existing RobotsTxtPolicy class, apply a polite delay (match the existing crawler.*.delay.ms
   convention), and note in a code comment that this is unofficial and may need maintenance if
   Google changes the page structure. Capture a weekly search-interest value per movie title for
   movies in movies_data_collection with a release_date in the next 90 days.

3. Trade-analyst predictions sub-source: extend the existing Sacnilk crawling (reuse
   SacnilkHtmlParser's approach) to also find and parse pre-release "opening day prediction"
   articles (a different page type from the box-office-result pages SacnilkCrawlerService already
   parses) for upcoming releases, storing the predicted figure/text as TRADE_PREDICTION rows.

4. Wire both behind their own properties (momentum.trends.enabled, momentum.tradepredictions.
   enabled, with matching .interval.hours/.delay.ms), following this repo's existing naming
   convention.

5. Tests: parsing against saved sample-page fixtures for both sub-sources; append-only insert
   behavior (never overwrites a prior capture); 90-day release-date window filter is correct.
```

---

## F16 — Marketing channel reallocation advisor

**Repo:** `AuraMath` (computation) + `AuraService` (surfacing)
**Covers:** "what to do" with remaining promotional effort during the marketing window
**Depends on:** nothing new

**Design notes**
- `EntityMarketingService`'s class comment already documents a per-platform reach-metric
  convention (X→`views_count`, YouTube→`likes_count`, Reddit→`num_comments`,
  Instagram→`like_count`). This feature turns that existing per-platform computation into a
  comparative efficiency ranking instead of raw counts — no new crawling, no new source.
- No advertising-spend data exists anywhere in this system, so this must be explicit that it
  ranks **organic engagement efficiency**, not paid-ad ROI — don't let the response imply
  something it can't know.

### Prompt
```
In AuraMath, add a marketing-channel efficiency ranking using data this system already collects —
no new crawling, and no spend data (none exists in this system, so be explicit that this ranks
organic engagement efficiency, not paid-ad ROI).

1. Add ChannelEfficiencyService reusing EntityMarketingService's existing per-platform reach
   metric convention (X->views_count, YouTube->likes_count, Reddit->num_comments,
   Instagram->like_count, as documented in EntityMarketingService's class comment): for a given
   entity, compute posts-per-platform and reach-per-post over a trailing window (default 30 days,
   parameterized), across x_posts/youtube_comments/reddit_posts/instagram_posts.

2. Rank platforms by reach-per-post descending. Add GET /api/marketing/channel-efficiency/
   {entityId}?windowDays=30 returning the ranking with raw counts alongside the ratio (so a
   platform with 2 posts and one viral hit doesn't outrank one with 200 consistent posts without
   that being visible).

3. Response must include a plain-text caveat field stating this measures organic engagement
   efficiency only, since this system has no advertising-spend data.

In AuraService:
4. Add a proxy method + GET /api/entities/{id}/channel-efficiency, same forwardGet+TtlCache
   pattern as other AuraMath proxies, ownership enforced via EntityAccessService.

5. Tests: ranking correctly orders by reach-per-post; low-sample platforms are still returned but
   with their raw post count visible; window parameter correctly bounds the query.
```

---

## What this batch deliberately does NOT cover

Being explicit about gaps so nothing here is mistaken for "the other 90 factors are handled":

| Category | Status |
|---|---|
| 1 — Narrative (1–15) | F1 covers all 15, but only 4 and 15 are trustworthy from synopsis alone. The other 11 (3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14) need `screenplayText` populated to score above confidence=LOW — this doc doesn't build a script-ingestion UI/upload flow, only the field and the engine's use of it; getting scripts into `screenplayText` is on the Production House. |
| 2 — Cast Capital (16–30) | F8 covers 20, 23 (diagnostic). F12 adds casting *recommendations*, F14 adds continuous per-talent risk tracking (22, 24). Persona fit (16), screen chemistry (18), multi-generational appeal (26) still have no data source — these would stay LLM-qualitative-only if ever added. |
| 3 — Production/Technical (31–45) | Not started. Genuinely unmeasurable before a trailer/teaser exists — needs a future video/audio-ML pass on actual promo assets once F5's timeline tells you they exist, not a text-only proxy. |
| 4 — Marketing (46–60) | Timing (F5), influencer targeting (F7), momentum (F15), channel efficiency (F16) now covered. Ticket-pricing/booking-driven signals (factor 59, and post-release factor 95) are explicitly **out of scope** — no free public API exists for BookMyShow/District-style booking data, and paid ticketing APIs are excluded by design. Dub/subtitle-quality proxy (86) now covered by **F1b** manual entry; brand cross-promotion (58) still has no source. |
| 5 — Calendar (61–70) | 61 (F2) and 62/66/68/69/70 (derivable, no build needed) covered. 63–65, 67 covered by **F1b** manual entry, not a crawler — F2's scope was narrowed to drop them. |
| 6 — Legal/Censorship (71–80) | Covered by **F1b** manual entry — no reliable free crawl target exists for certification/bans/disputes as a whole. A CBFC public certificate-search crawl remains a possible *future* upgrade for the certification piece specifically, not a near-term plan. |
| 7 — Financial/Distribution (81–90) | 82–87, 90 now covered by **F1b** manual entry (private deal terms, financing rates, producer solvency — this system was never going to crawl these). 88/89 stay derivable from `production_companies`. F9 only uses the budget/comps piece for prediction; F16 is explicit that it ranks organic engagement, not ad spend, because no spend data exists here. |
| 8 — Post-Release (91–100) | Explicitly out of scope for this doc — this remains the job of the existing MentionService / SentimentAlertService / MobilizeAlliesService / TopSpreaderLookupService stack once a movie releases. Nothing to build. |

## Suggested execution sequence

1. **F1** (AuraService) — foundational feature store
2. **F1b** (AuraService, after F1) — manual entry for the ~21 Production-House-only factors
3. **F2** + **F3** (AuraDataFiller, parallel to each other and to F1) — calendar data
4. **F4** (AuraService, after F2+F3) — release-date optimizer
5. **F8** (AuraService, after F1) — talent features into the feature store
6. **F12** (AuraDataFiller extension + AuraService, after F8 and F3) — casting recommendations
7. **F9** (AuraMath + AuraService, after F1+F8) — prediction, retires the mock endpoint
8. **F11** (AuraMath + AuraService, after F9) — lever simulator
9. **F13** (AuraService, independent — can actually be built anytime, even first) — stage tracking
10. **F14** (AuraMath + AuraService, after F13) — talent sentiment radar
11. **F5** (AuraMath, independent — can run anytime after confirming the YouTube promo table)
12. **F6** (AuraDataFiller, independent) → **F7** (AuraMath + AuraService)
13. **F15** (AuraDataFiller, independent) — momentum crawler
14. **F16** (AuraMath + AuraService, independent) — channel efficiency
15. **F10** (AuraService, last — pulls F1/F4/F5/F7/F9 together; revisit afterward to fold in
    F11/F12/F14/F16 once they exist, but that's optional polish, not required to ship F10 itself)

Each prompt is self-contained but assumes the prior features in its dependency chain are merged.
Run one feature per branch/PR per repo, review, then proceed.
