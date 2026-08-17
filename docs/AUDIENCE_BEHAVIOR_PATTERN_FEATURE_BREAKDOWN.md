# Audience Behavior Pattern Intelligence — Feature Breakdown

A build plan for **post-release audience-behavior pattern mining**: which non-obvious marketing
actions correlate with maximum viewership, which users' engagement actually precedes growth
(not just who is loudest), what winning campaigns did differently in sequence, and how to chain
those findings (A→B→C→D→E) into an auditable, statistically-honest causal narrative — decomposed
into **10 sequenced features** across `AuraMath` (compute/storage — owns the raw mention tables)
and `AuraService` (surfacing/ownership/report assembly), plus two standalone Python batch jobs for
the two analyses that genuinely need real stats libraries (Granger causality, sequential pattern
mining) rather than a hand-rolled Java port.

> **Scope boundary vs. the existing docs.** `PREDICTIVE_FACTOR_DATA_AUDIT.md` /
> `PREDICTIVE_LAUNCH_FEATURE_BREAKDOWN.md` cover *pre-release* prediction (what will this movie do
> before it exists) and explicitly declare Category 8 — Post-Release Dynamics — out of scope,
> deferring it to "the existing `MentionService`/`SentimentAlertService`/`TopSpreaderLookupService`
> monitoring stack." **This doc is that deferred half.** Nothing here predicts box office; all of
> it explains and acts on audience behavior *after* mentions exist. Where a feature needs a
> pre-release input (e.g. `entity_factor_scores`), it treats it as optional and not yet built (see
> below), never as a hard dependency.

> **Mathematical honesty is load-bearing here, not decoration.** Every "pattern" this doc produces
> is a statistical association with a documented test, p-value, effect size, and sample size — not
> a proof of causation. `managed_entities` had 38 rows as of the last live audit (2026-07-17) —
> that is the entire comps pool for cross-movie analysis. Every feature below states an explicit
> minimum-N gate and a graceful "not enough tracked history yet" fallback, the same discipline
> `PREDICTIVE_LAUNCH_FEATURE_BREAKDOWN.md` already uses for its own low-sample cases. **Confirm
> current row counts against the live DB before tuning any threshold in these prompts** — they are
> starting points, not final constants.

## Already built — do not duplicate

Research against the live codebase (both repos, 2026-08-16/17) found most of the "which users to
target" half of the user's ask is **already shipped**:

- `AuraMath`'s `LanguageMarketingAPI` → proxied at `GET /v1/marketing/language/{language}/users`
  and `.../movie/{movieName}/users` — ranked, language-filtered user lists with
  `engagement_rating`/`tribe_label`/`platform_handles` already joined in.
- `TopSpreaderLookupService` (`/v1/marketing/genre|party|celebrity/.../super-spreaders`,
  top-50-spreaders), `MovieBuffLookupService` (`/movie-buffs/{keyword}`), `ViralSeedLookupService`
  (`/viral-seeds`, Hawkes-α + MOI + cross-platform reach), `MobilizeAlliesService` (DM the
  positive-sentiment spreaders), `LookalikeDiscoveryService`/`GenreLookalikeService` (tribe-based
  lookalike expansion from a seed author) — all real, all live.
- `GraphPopulationService`/`UserGraphController` (`GET /v1/graph/users?language=&movie=`) — a
  populated, queryable `graph_nodes`/`graph_edges` user↔movie graph with `POSTED_ABOUT`/
  `RETWEETED` edges, weighted by `EngagementScoreCalculator`'s `3·comments + 2·shares + 1.5·likes +
  1·views` formula.
- `AudiencePatternService` (`/api/marketing/audience-patterns`) — timing (hour/day-of-week
  engagement buckets) and cohort (industry/language aggregate sentiment+engagement) patterns.
- `AudiencePulseAspectsService` — aspect-based "people love / people are concerned about" chips.
- `RecommendedActionCandidateServiceImpl`'s 12 existing candidate slugs — all pre-release
  timing/reach factors (`trailer-teaser-timing`, `peak-engagement-hours`, `movie-buff-outreach`,
  `viral-seed`, etc.), all-Java, no LLM in candidate generation.

None of this is rebuilt below. **F6** and **F8** extend two of these (adds one field to the
language-user response; adds new candidate slugs to the existing recommender) rather than standing
up parallel endpoints.

## Facts these prompts assume

- Same shared `aura` Postgres DB as the other docs in this workspace; `AuraMath` writes with raw
  JDBC (`ensureSchema()` + `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, no Flyway — see
  `ConflictBalanceService`/`RetweetResolver`/`UserEngagementRatingService` for the exact idiom to
  copy); `AuraService` is `spring.jpa.hibernate.ddl-auto=update`, also no migration tool.
- Raw platform tables and their real engagement columns (confirmed live, `AuraSocialMediaService`
  `DatabaseService.java`): `x_posts(comment_count, likes_count, views_count)`,
  `youtube_comments(reply_count, likes_count)` (a YouTube row here is a *comment*, no view/share
  count of its own — the video's `view_count` lives separately on `youtube_videos`),
  `reddit_posts(num_comments, score)` (`score` = net upvotes, already used as Reddit's likes-proxy
  elsewhere), `instagram_posts(comments_count, like_count, views, reshare_count)`. **Note:**
  `AuraMath/docs/user-language-graph-plan.md`'s `EngagementScoreCalculator` adapter currently hard-codes
  `shares=0` for Instagram even though `instagram_posts.reshare_count` exists as a column — confirm
  with a live `SELECT count(*) FILTER (WHERE reshare_count > 0)` whether it's actually populated
  before F1 below decides whether to use it; if it's real data being silently dropped, that's a
  one-line fix to `EngagementScoreCalculator`'s Instagram adapter, cite it as a fix in F1 rather
  than re-deriving engagement weighting from scratch.
- All four raw tables plus `mentions` carry `author_type`, `content_intent`, `topic_category`
  (confirmed via `MentionRepository.java` — backs the ungated
  `/api/dashboard/{id}/author-type-breakdown` etc. endpoints) and `sentiment_category` +
  (`mentions.sentimentScore` / raw-table numeric score).
- **Net sentiment score has one canonical formula in this codebase** (`DashboardService.java`):
  `positiveMentions / negativeMentions` (0.0 if no negatives) — a **count ratio**, not a
  subtraction and not derived from the numeric per-post score. Every feature below that says "net
  sentiment" reuses this exact formula over whatever time window is stated, never a new
  definition.
- `user_identity_link(global_user_id, normalized_author)` already resolves cross-platform authors
  (built by `CrossPlatformIdentityResolver`); normalize with `lower(author)` + strip
  non-alphanumerics, exactly as `GenreLookalikeService.normalize()` does. **Do not** copy
  `GenreMarketingAPI.potentialViewers`'s direct unnormalized join — documented as a latent bug in
  `user-language-graph-plan.md`.
- `marketing_target_profiles(global_user_id, platform_handles, tribe_label, influence_rank,
  engagement_score_raw, engagement_rating, top_genres, moi_score)` is AuraMath's per-user
  enrichment table, refreshed on a scheduler (`MarketingEnrichmentScheduler`).
- `SentimentAlertService`'s existing SPIKE detection rule — `currentRatio > baselineRatio * 1.5`
  (`SentimentAlertService.java:36,85`) — is the codebase's one existing precedent for "what counts
  as a regime-change event." F3/F7 reuse this threshold rather than inventing a new one.
- `Checkpoint` (`AuraService/entity/Checkpoint.java`) today is `managedEntity, checkpointDate,
  description (≤20 chars, freeform)` — no type field. F2 adds one.
- `entity_factor_scores`, `FactorScoringEngine`, `screenplayText`, `ProductionStage`,
  `entity_stage_history` — all proposed in `PREDICTIVE_LAUNCH_FEATURE_BREAKDOWN.md` F1/F1b/F13 —
  **do not exist in the codebase yet** (confirmed by grep, 2026-08-17). Anything below that could
  use them (F5) treats them as optional/LEFT JOIN, never a hard dependency.
- `InfluenceMetricCalculator.calculateMoi` = RMS of per-post `(likes+comments)/views` — an
  *efficiency* metric, distinct from `EngagementScoreCalculator`'s raw weighted-sum *reach* metric.
  `SeedScoreCalibrator` already blends `moi` + `log1p(reach)` into a self-calibrating composite
  (percentile-targeted weights) for viral-seed selection — F4/F6 below build *new* signals
  (precedence-in-time, causal lift), not a third blend of the same two inputs.
- **LLM convention, hard rule for every feature below:** `LLMService`/`RestTemplate` →
  `${llm.url}`, prompt template read from `application.properties` under
  `llm.prompt.generate.<x>`, response parsed via `objectMapper.readTree(...)`, parse failure
  rethrown as a 400. **The LLM never emits a number.** Every prompt in this doc that touches an LLM
  splits work into (a) Java/Python-computed candidates — every score, p-value, effect size,
  sample size is arithmetic, never model-guessed — and (b) an LLM pass that only *selects among* or
  *phrases* those precomputed candidates. No `score`/`confidence`/`pValue` field is ever asked of
  the LLM's JSON schema.
- Mockito can't mock concrete classes on this JDK (Java 25) — mock interfaces only, in every repo.

## How this maps to the questions that motivated it

| Question asked | Feature(s) |
|---|---|
| Which users will help increase popularity / which to reach out to | Already built (see above) + **F6** adds the one thing missing: does this user's *early* engagement actually precede growth, not just how loud are they |
| Which users, by movie language, help reach maximum viewership | Already built (`LanguageMarketingAPI`) — **F6** enriches it |
| What did other movies do to achieve maximum viewership | **F7** (cross-movie sequence mining) |
| Which users commented most on high-net-sentiment threads and helped achieve maximum viewership | **F6**, specifically gated on high-net-sentiment periods |
| A causes B causes C causes D causes E (E = max viewership) | **F1** (defines E), **F3** (defines A–D candidate series), **F4** (finds the chain) |
| Non-obvious actions (not "do a trailer/music launch") | **F2** (makes the obvious actions a control variable), **F5** (finds what's left after controlling for them) |
| Recommend actions to the marketing team | **F8**, **F9** (wired into the existing recommender), **F10** (report) |

---

## F1 — Viewership Momentum Index (VMI): the outcome variable everything else predicts

**Repo:** `AuraMath`
**Depends on:** nothing
**Why first:** Every later feature needs a single, honest, cross-movie-comparable definition of
"maximum viewership." Ticketing/box-office data covers 13% of `movies_data_collection` rows
(per the predictive audit) and doesn't exist at all for social-audience behavior. The only real,
densely-populated signal this app has is engagement on the tracked mentions themselves — so VMI is
explicitly a **social-momentum proxy**, not a box-office number, and every downstream feature must
say so rather than imply otherwise.

**Design notes**
- Reuse `EngagementScoreCalculator`'s existing per-post formula and per-platform adapters
  verbatim (don't re-derive engagement weighting) to get a per-mention engagement score.
- `dailyEngagementVolume(entity, day) = Σ engagement score of every mention of that entity posted
  that day`, computed from `mentions` joined to whichever raw table matches `platform` (same join
  pattern `MentionEngagementResolverImpl` already uses).
- Two outcome numbers per entity, both persisted: `peakDailyEV` (the single highest day —
  "moment of maximum viewership") and `cumulativeEV(day)` running total ("campaign viewership to
  date"). Do not collapse to one number; F4/F5/F7 need the time series, not just the peak.
- Cross-movie comparability: z-score each entity's daily EV against its own cohort (same
  `industry`+`language`, aligned on **days-since-first-tracked-mention**, not calendar date) —
  reuse `AudiencePatternService`'s existing industry/language cohort grouping rather than
  reinventing the grouping key.
- Persist into a new table `entity_daily_vmi`: `entity_id, day_index (int, days since first
  mention), calendar_date, daily_engagement_volume, cohort_zscore, cumulative_engagement_volume,
  computed_at`. Upsert on `(entity_id, day_index)`.

### Prompt
```
In AuraMath, add a Viewership Momentum Index (VMI) — a social-engagement-based proxy for
"maximum viewership," since real box-office/ticketing data doesn't exist in this system. Reuse
EngagementScoreCalculator's existing per-post scoring formula and per-platform adapters (X/YouTube/
Reddit/Instagram) verbatim — do not re-derive engagement weighting.

First, run SELECT count(*) FILTER (WHERE reshare_count > 0) FROM instagram_posts and tell me the
result — EngagementScoreCalculator's Instagram adapter currently hard-codes shares=0 even though
the column exists. If reshare_count has real non-zero data, fix the adapter to use it (one line)
before proceeding; if it's empty, leave it as-is and say so.

1. Add VmiComputationService (package com.lit.fire.flame, ensureSchema() + ALTER TABLE IF NOT
   EXISTS idiom like ConflictBalanceService/RetweetResolver): for every managed_entities row of
   type MOVIE, compute dailyEngagementVolume(entity, day) = sum of EngagementScoreCalculator's
   per-mention score across every mention/x_post/youtube_comment/reddit_post/instagram_post of that
   entity, grouped by the post's own date. Index days as day_index = days since that entity's
   first tracked mention (not calendar date), so movies trackable for different real-world date
   ranges are still comparable turn-by-turn.

2. Compute cohort z-scores: group entities by (industry, language) — same grouping
   AudiencePatternService's getCohortPattern already uses — and for each (cohort, day_index) pair,
   z-score that entity's dailyEngagementVolume against the same day_index across its cohort-mates.
   If a cohort has fewer than 4 entities at a given day_index, skip the z-score (null) rather than
   computing one from a near-empty sample — state this threshold in a comment.

3. Persist to entity_daily_vmi: entity_id, day_index, calendar_date, daily_engagement_volume,
   cohort_zscore (nullable), cumulative_engagement_volume (running sum), computed_at. Upsert on
   (entity_id, day_index).

4. Add a method peakDay(entityId) returning the day_index/calendar_date with the highest
   daily_engagement_volume ("moment of maximum viewership") and cumulativeToDate(entityId)
   returning the latest cumulative_engagement_volume ("campaign viewership so far") — both used by
   F4/F5/F7/F10 later in this doc.

5. Wire into the existing scheduler pattern (MarketingEnrichmentScheduler or a sibling scheduled
   method) plus a POST /api/admin/run-vmi-computation trigger, matching UserEngagementRatingService's
   pattern.

6. Tests: a fixed synthetic mentions fixture produces the expected per-day EV and correct
   day_index alignment; cohort z-score is null when fewer than 4 cohort-mates exist at that
   day_index; upsert is idempotent on re-run.
```

---

## F2 — Typed checkpoints (the control variable for "obvious" actions)

**Repo:** `AuraService`
**Depends on:** nothing
**Why this exists:** F5's whole job is finding *non-obvious* levers, which requires first knowing,
structurally, which days had an *obvious* one (trailer, teaser, music launch, promo event). Today
`Checkpoint.description` is 20 characters of freeform text — not queryable as a control variable.

**Design notes**
- Add `CheckpointType { TEASER, TRAILER, MUSIC_LAUNCH, PROMO_EVENT, CAST_ANNOUNCEMENT,
  PRESS_MEET, OTHER }` to `Checkpoint`, nullable, defaulting existing rows to `OTHER` via the
  same idempotent-backfill pattern other docs in this workspace use (no Flyway).
- Keep `description` as-is (free text label alongside the new type) — this is additive, not a
  replacement.

### Prompt
```
In AuraService, add a CheckpointType enum to the Checkpoint entity so marketing-timeline
checkpoints become a structured, queryable control variable, not just freeform text.

1. Add enum CheckpointType { TEASER, TRAILER, MUSIC_LAUNCH, PROMO_EVENT, CAST_ANNOUNCEMENT,
   PRESS_MEET, OTHER }. Add a nullable checkpointType column to Checkpoint, defaulting to OTHER.
   Add an idempotent startup backfill (ApplicationRunner, same pattern as
   EntityOwnerBackfill/EntityImageBackfill) setting checkpointType = OTHER for any existing row
   with a null value.

2. Update CheckpointService's create/update methods to accept an optional checkpointType
   (default OTHER if omitted) alongside the existing description field. Update the
   managed_entities/checkpoints DDL doc in the README.

3. Tests: backfill sets OTHER exactly once and is idempotent on a second run; create/update
   round-trip the type; omitting the type on create defaults to OTHER. Mock interfaces only.
```

---

## F3 — Per-entity daily audience-behavior feature series

**Repo:** `AuraMath`
**Depends on:** F1 (day-indexing convention), reuses existing `engagement_rating`/
`SentimentAlertService`'s SPIKE threshold
**Why this exists:** F1 defines the outcome (E). This defines the candidate causal steps
(A, B, C, D) — the actual behavioral signals that might precede a viewership jump, time-binned the
same way as F1 so they can be lag-tested against it.

**Design notes**
- Per `(entity, day_index)`, alongside F1's `dailyEngagementVolume`, compute:
  - `commentVelocity` — count of mentions that day.
  - `contentIntentMix` — jsonb map of `content_intent → count` that day (column already exists on
    all four raw tables).
  - `netSentimentDelta` — this day's `netSentimentScore` (canonical positive/negative count-ratio
    formula, see top-of-doc) minus the prior day's, over a **trailing 7-day window** ending that
    day (not single-day counts, which are too sparse per day for most tracked entities).
  - `spreaderTierShare` — fraction of that day's `dailyEngagementVolume` contributed by authors
    whose `marketing_target_profiles.engagement_rating` is in the top decile *of all resolved
    users*, not just this entity's — reuse F1's identity-resolution join.
  - `cascadeDepth` — that day's average number of `RETWEETED` edges per original post reached
    (read from AuraMath's already-populated `graph_edges`, built by `GraphPopulationService`).
  - `spilloverEvent` — nullable platform name: the first platform, if any, whose day's EV crossed
    `1.5×` its own trailing 7-day average that day — literally `SentimentAlertService`'s existing
    SPIKE multiplier (`1.5`), reused for consistency, applied to engagement volume instead of
    sentiment ratio.
- Persist into `entity_daily_behavior_features`, one row per `(entity_id, day_index)`, FK-aligned
  with `entity_daily_vmi`.

### Prompt
```
In AuraMath, add per-entity daily audience-behavior feature computation, aligned to F1's
entity_daily_vmi day_index convention (this doc assumes F1 — VmiComputationService,
entity_daily_vmi — is already built).

Add BehaviorFeatureComputationService (package com.lit.fire.flame, same ensureSchema idiom):

1. For each (entity, day_index) already present in entity_daily_vmi, compute:
   - commentVelocity: count of that day's mentions across all four raw tables.
   - contentIntentMix: jsonb {content_intent: count} for that day (content_intent column already
     exists on x_posts/youtube_comments/reddit_posts/instagram_posts).
   - netSentimentDelta: trailing-7-day netSentimentScore (positiveMentions/negativeMentions count
     ratio — reuse DashboardService's exact formula, do not redefine it) for the window ending this
     day, minus the same computed for the window ending the prior day.
   - spreaderTierShare: fraction of this day's engagement volume (EngagementScoreCalculator sum)
     contributed by authors (resolved via user_identity_link, normalized the same way
     GenreLookalikeService.normalize() does) whose marketing_target_profiles.engagement_rating is
     at or above the global 90th percentile.
   - cascadeDepth: average count of RETWEETED graph_edges per originating POSTED_ABOUT post for
     that day, reading GraphPopulationService's already-populated graph_edges table.
   - spilloverEvent: nullable — the platform (X/YOUTUBE/REDDIT/INSTAGRAM) whose engagement volume
     that day first exceeded 1.5x its own trailing-7-day average (reuse
     SentimentAlertService.SPIKE_MULTIPLIER = 1.5's exact threshold value for consistency across
     the codebase, even though this is a different repo/language — cite it in a comment), null if
     no platform crossed it that day.

2. Persist to entity_daily_behavior_features: entity_id, day_index, comment_velocity,
   content_intent_mix (jsonb), net_sentiment_delta, spreader_tier_share, cascade_depth,
   spillover_event (nullable text), computed_at. Upsert on (entity_id, day_index).

3. Wire into the same scheduler as F1, running immediately after VmiComputationService each cycle
   (this service reads entity_daily_vmi's day_index alignment as a prerequisite).

4. Tests: netSentimentDelta matches DashboardService's ratio formula on a fixed fixture;
   spreaderTierShare correctly excludes unresolved authors; spilloverEvent only fires past the 1.5x
   threshold, not below it.
```

---

## F4 — Causal precedence engine (Granger-style lag discovery)

**Repo:** standalone Python batch job, writing into the shared `aura` DB (no new service/API in
either Java repo — this is a scheduled offline analysis, following the "new table, instantly
readable by every other service" philosophy already established in this workspace)
**Depends on:** F1, F3
**Why this exists:** This is the literal "A caused B, B caused C..." ask. True causal inference
from observational social data is not achievable — what *is* achievable, and honest, is
**Granger-style statistical precedence**: does series X's past values improve the prediction of
series Y's future values, beyond what Y's own past already explains, at a specific time lag,
pooled across multiple comps movies with multiple-comparison correction. That is what this feature
computes and how it must always be described downstream — "statistically precedes," never
"causes," in any user-facing text this doc's later features generate.

**Design notes**
- Language: Python (`statsmodels`, `pandas`, `scipy`, `psycopg2`/`SQLAlchemy`) — this is the one
  piece of math genuinely better served by an existing, audited stats library than a hand-rolled
  Java port of Granger causality.
- Candidate series per entity (from F1+F3, in day_index order): `dailyEngagementVolume`,
  `commentVelocity`, `netSentimentDelta`, `spreaderTierShare`, `cascadeDepth`, and one binary
  series per `content_intent` value observed (count that day). Test every ordered pair (X→Y),
  `X ≠ Y`, at lags 1–7 days, using `statsmodels.tsa.stattools.grangercausalitytests`.
- **Minimum-N gate:** only test an entity if it has ≥ 21 days of `entity_daily_vmi` history (3
  weeks — the minimum for a lag-7 test to have any degrees of freedom left). Confirm the live count
  of entities meeting this bar before running; if it's under ~6, say so explicitly in the job's
  output rather than reporting single-entity "findings" as a pattern.
- **Pool across the cohort, don't trust one entity's p-value.** For each `(X, Y, lag)` triple,
  collect the per-entity p-value from every qualifying entity in the same `(industry, language)`
  cohort (or the global pool if a cohort has too few), combine them via **Fisher's method**
  (`-2·Σln(pᵢ)` ~ χ² with `2n` degrees of freedom) — standard technique for combining independent
  p-values from repeated, independent tests of the same hypothesis.
- **Multiple-comparison correction.** Testing many `(X, Y, lag)` triples inflates the false-positive
  rate — apply Benjamini-Hochberg FDR correction (`statsmodels.stats.multitest.multipletests`,
  `method='fdr_bh'`) across all pooled p-values before calling anything significant. Use `q < 0.10`
  as the surviving threshold (looser than the conventional 0.05, explicitly because comps sample
  sizes here are small — state this choice plainly in the job's log/output, don't hide it).
- Also record **effect size**: the R² added by including the lagged X term over an
  autoregressive-only baseline model of Y (this is what `grangercausalitytests` reports natively
  via its `ssr_ftest`/`ssr_chi2test` — pull the F-statistic-derived partial R², not just the
  p-value, so a surviving edge can be ranked by how much it actually explains, not only whether it
  cleared the significance bar).
- **Chain construction:** build a directed graph of surviving `(X, Y, lag, q, effectSize,
  nEntities)` edges. Find paths of length ≤ 5 ending at `dailyEngagementVolume` (F1's E), ranked by
  the product of `(1 - q)` across the path (a simple, defensible way to combine confidence across
  hops without pretending it's a joint probability). Return the top 10 chains per cohort.
- Persist to `causal_precedence_edges`: `from_series, to_series, lag_days, pooled_p_value,
  fdr_q_value, effect_size_r2, n_entities_supporting, cohort (industry|language or 'ALL'),
  computed_at`. This table is the entire interface other services need — no HTTP call required
  (same shared-DB philosophy as the rest of this workspace).

### Prompt
```
Write a standalone Python script (not part of either Java repo — a scheduled batch job connecting
directly to the shared `aura` Postgres DB) that discovers statistical precedence chains among
audience-behavior series, using AuraMath's entity_daily_vmi (F1) and entity_daily_behavior_features
(F3) tables as input. Use pandas, statsmodels, scipy, psycopg2 (or SQLAlchemy).

IMPORTANT framing: this computes Granger-style statistical precedence — does series X's past
values improve prediction of series Y's future beyond Y's own history, at a given lag — NOT
causation. Every place this gets described (logs, persisted rows, eventual UI text) must say
"statistically precedes" or "associated with, lagged by N days," never "causes."

1. Load, per entity with >= 21 days of entity_daily_vmi history (query and print this count first;
   if fewer than ~6 entities qualify, print a clear warning that findings below this scale are
   exploratory, not reliable, and continue anyway so the pipeline is testable, but flag it loudly):
   daily_engagement_volume, comment_velocity, net_sentiment_delta, spreader_tier_share,
   cascade_depth, plus one binary daily series per distinct content_intent value observed in that
   entity's content_intent_mix jsonb column, in day_index order.

2. For every ordered pair (X, Y) of these series where X != Y, run
   statsmodels.tsa.stattools.grangercausalitytests(data[[Y, X]], maxlag=7) per qualifying entity.
   Extract, per lag 1-7: the p-value (ssr_ftest) and the F-statistic (used to derive effect size in
   step 4).

3. Group entities by (industry, language) cohort (same grouping key as AuraMath's
   AudiencePatternService cohort logic — query managed_entities for industry/language per entity).
   For each (X, Y, lag, cohort) combination, pool the per-entity p-values via Fisher's method:
   chi2_stat = -2 * sum(ln(p_i)), df = 2 * n, combined_p = 1 - chi2.cdf(chi2_stat, df) (scipy.stats.chi2).
   If a cohort has fewer than 3 qualifying entities, pool globally across ALL cohorts instead and
   label cohort='ALL' for that row.

4. Apply Benjamini-Hochberg FDR correction (statsmodels.stats.multitest.multipletests, method=
   'fdr_bh') across ALL pooled p-values computed in this run (not per-cohort separately — one
   correction pass over the whole batch, since that's the actual number of hypotheses tested).
   Keep only rows with fdr_q_value < 0.10. Also compute an effect-size R2 (from the F-statistic:
   partial R2 = F / (F + df_resid) is a standard approximation — use it, and say in a comment that
   it's an approximation of the variance the lagged term adds over the autoregressive baseline).

5. Persist surviving edges into a new table causal_precedence_edges (CREATE TABLE IF NOT EXISTS,
   run once at script start): from_series, to_series, lag_days, pooled_p_value, fdr_q_value,
   effect_size_r2, n_entities_supporting, cohort, computed_at. Delete-and-replace this run's cohort
   rows before inserting (idempotent re-run), don't append duplicates.

6. Chain construction: build a directed graph (networkx) from the surviving edges. Find all simple
   paths of length <= 5 hops ending at daily_engagement_volume. Score each path by the product of
   (1 - fdr_q_value) across its edges. Print (and separately persist to a causal_precedence_chains
   table: cohort, chain_json (ordered list of {from,to,lag,q,effectSize}), path_score, computed_at)
   the top 10 chains per cohort.

7. Add a requirements.txt (pandas, statsmodels, scipy, psycopg2-binary, networkx) and a README
   section on how to run it (connection string via env var, not hardcoded) and how often it's
   intended to run (weekly is reasonable given how slowly entity_daily_vmi accumulates new days).

8. Add a small pytest suite with a synthetic pandas DataFrame (a known lagged relationship injected
   between two series) verifying: the injected relationship is detected as significant; FDR
   correction actually reduces the surviving edge count vs. uncorrected; Fisher's-method pooling
   matches a hand-computed value for a small fixed set of p-values.
```

---

## F5 — Non-obvious lever miner (residual/anomaly analysis)

**Repo:** standalone Python batch job, same DB, same conventions as F4
**Depends on:** F1, F2, F3; optionally `entity_factor_scores` (soft — LEFT JOIN, not yet built)
**Why this exists:** This is the direct answer to "actions that are not evident." The method:
model viewership from only the *obvious* inputs (typed checkpoints, cohort, budget if available),
take the leftover (residual) that the obvious model can't explain, and mine what's systematically
different about the audience-behavior *process* — not the marketing calendar — between movies that
overperformed vs. underperformed that obvious-factors baseline.

**Design notes**
- Target: `log(cumulativeEV)` at day 30 (or the latest available day if an entity has fewer than
  30 tracked days — use whatever's available, weight by how much history exists, don't require a
  fixed horizon that would drop most currently-tracked entities).
- "Obvious" feature set: count of each `CheckpointType` from F2 (has a trailer, has a music
  launch, etc.), `(industry, language)` cohort dummies, `budget` from `movies_data_collection` if
  the entity name-matches a comp row (LEFT JOIN, null-safe — per the predictive audit, only ~32 of
  38 tracked entities match anything in `movies_data_collection` at all), and
  `entity_factor_scores` aggregate-by-category averages *if that table exists* (`information_schema`
  check first — this table is proposed but unbuilt as of this writing; degrade to omitting it
  entirely rather than erroring).
- Model: plain OLS (`statsmodels.api.OLS`) of `log(cumulativeEV)` on the obvious features — stay
  transparent/auditable, same "not a black box" philosophy the predictive doc insists on for its
  own comps model. Residual = actual − predicted.
- Compare top-residual-quartile vs. bottom-residual-quartile entities on every F3 behavior-feature
  aggregate (mean `spreaderTierShare` in the first 2 weeks, mean `cascadeDepth`, dominant
  `content_intent` mix, most common `spilloverEvent` platform-order pattern) using
  **Mann-Whitney U test** (nonparametric — appropriate given small, non-normal samples) per
  feature, with the same `q < 0.10` FDR-corrected bar as F4.
- **Minimum-N gate:** require at least 8 entities total (so each quartile has ≥ 2) before running
  this at all — below that, every "finding" is describing 1–2 movies, which is an anecdote, not a
  pattern; say so plainly and skip.
- Persist to `nonobvious_lever_findings`: `feature_name, cohort, u_statistic, p_value, fdr_q_value,
  direction (HIGHER_IN_OVERPERFORMERS|LOWER_IN_OVERPERFORMERS), n_entities, computed_at`.

### Prompt
```
Write a standalone Python script (same conventions as F4: shared `aura` DB, pandas/statsmodels/
scipy, scheduled batch, not part of either Java repo) that finds non-obvious audience-behavior
levers — behavioral patterns associated with over-performing a "predicted from obvious factors
alone" viewership baseline.

1. Build a feature table, one row per managed_entities row of type MOVIE with >= 14 days of
   entity_daily_vmi history: target = ln(latest cumulative_engagement_volume from entity_daily_vmi,
   whatever day is latest for that entity). Require >= 8 qualifying entities total; if fewer, print
   a clear message that there isn't enough tracked history for this analysis yet and exit without
   writing anything.

2. Obvious-factor features per entity: count of each Checkpoint.checkpointType value (F2) up to the
   day the target was measured; (industry, language) as one-hot cohort dummies; budget from
   movies_data_collection where the entity's name matches a row (LEFT JOIN, null -> 0 with a
   separate has_budget_data boolean flag, don't silently treat missing budget as zero-cost). Before
   querying, run SELECT to_regclass('entity_factor_scores') to check whether that table exists yet
   (it's proposed in PREDICTIVE_LAUNCH_FEATURE_BREAKDOWN.md F1 but unbuilt as of 2026-08-17) — if
   it exists, LEFT JOIN in the average score per factor category as additional obvious-features;
   if not, skip this source entirely and note in the script's output that it wasn't available.

3. Fit statsmodels.api.OLS(target ~ obvious features). Compute residual = actual - predicted per
   entity. Rank entities by residual; top quartile = "overperformers relative to the obvious
   model," bottom quartile = "underperformers."

4. For each entity_daily_behavior_features aggregate — mean spreader_tier_share over the first 14
   days, mean cascade_depth over the first 14 days, the modal content_intent key from
   content_intent_mix summed across the period, and the most common spillover_event value (or
   "NONE") — run a Mann-Whitney U test (scipy.stats.mannwhitneyu) comparing the top-quartile vs.
   bottom-quartile entities' values for numeric features, and a Fisher's exact test
   (scipy.stats.fisher_exact) on a 2x2 presence/absence table for categorical ones (e.g., "did this
   entity's most common spillover platform = REDDIT").

5. Apply Benjamini-Hochberg FDR correction across all tests run in this batch
   (statsmodels.stats.multitest.multipletests, fdr_bh). Keep q < 0.10.

6. Persist surviving findings to nonobvious_lever_findings (CREATE TABLE IF NOT EXISTS at script
   start, delete-and-replace this run's rows): feature_name, cohort ('ALL' — this analysis pools
   across cohorts given the small N; note this explicitly rather than pretending per-cohort
   granularity that the sample can't support), test_statistic, p_value, fdr_q_value, direction
   (HIGHER_IN_OVERPERFORMERS or LOWER_IN_OVERPERFORMERS, from comparing group medians), n_entities,
   computed_at.

7. requirements.txt + README section, same as F4. Add pytest coverage: a synthetic feature table
   with a known injected difference between two groups is detected; the has_budget_data flag
   correctly distinguishes "no budget data" from "budget is genuinely zero"; the entity_factor_scores
   existence check correctly skips that source when the table is absent (mock the to_regclass
   result).
```

---

## F6 — Causal-lift user score (who's early-and-right, not just loud)

**Repo:** `AuraMath`
**Depends on:** F1, F3, existing `marketing_target_profiles`/`user_identity_link`
**Why this exists:** Directly answers "which users commented most on high-net-sentiment threads
and helped achieve maximum viewership" — as a *tested* claim, not a raw engagement-count ranking
(which `engagement_rating`/`MOI`/`tribe_label` already give you). This scores whether a user's
early presence on a high-net-sentiment day is followed by above-baseline growth, distinguishing
"was loud" from "showed up before it took off."

**Design notes**
- Qualifying event: a `(user, entity, day)` triple where the user has ≥ 1 mention that day, **and**
  that entity's trailing-7-day net sentiment ratio (canonical formula) on that day is in the top
  quartile of that entity's own history to date (per-entity relative threshold, not a fixed global
  cutoff — a small entity's "good day" and a large entity's "good day" aren't the same absolute
  number).
- Local counterfactual: since there's no true control group, use the entity's own pre-event trend
  (linear fit of `cumulativeEV` over the 7 days *before* the qualifying day) to project an expected
  `cumulativeEV` 3 days later; compare to the actual. `lift = (actual − projected) / max(projected,
  ε)`. This is an interrupted-trend estimate, not a randomized-experiment estimate — say so in the
  persisted row's semantics/doc comment.
- Aggregate per `global_user_id`: mean `lift` across all their qualifying events, **inverse-
  variance weighted** if they have ≥ 2 events (standard meta-analytic pooling — down-weights a
  single noisy observation relative to a consistent pattern across several).
- **Confidence gate:** `HIGH` only with ≥ 3 qualifying entity-events for that user; `LOW` (still
  computed, still stored, just labeled) below that — never silently hide a low-confidence score,
  never present it as equivalent to a high-confidence one.
- Persist to `user_causal_lift_scores`: `global_user_id, causal_lift_score, n_qualifying_events,
  confidence, last_computed_at`.

### Prompt
```
In AuraMath, add UserCausalLiftScoreService (package com.lit.fire.flame, same ensureSchema +
scheduler idiom as UserEngagementRatingService) computing, per user, whether their engagement
during an entity's high-net-sentiment periods is followed by above-baseline viewership growth —
distinct from raw engagement_rating (loudness) or moi_score (efficiency), which already exist.

Depends on entity_daily_vmi (F1) and entity_daily_behavior_features (F3) already existing.

1. For each entity, compute its own trailing-7-day net sentiment ratio (DashboardService's exact
   positiveMentions/negativeMentions formula) for every day_index, and find that entity's own
   top-quartile threshold across its history to date (per-entity relative cutoff, not a fixed
   global one).

2. For each (entity, day_index) where that day's trailing net-sentiment ratio is >= the entity's
   own top-quartile threshold: find every resolved global_user_id (via user_identity_link,
   normalized the same way GenreLookalikeService.normalize() does) with >= 1 mention of that entity
   that day. This is a "qualifying event" (user, entity, day_index).

3. For each qualifying event, fit a linear trend of cumulative_engagement_volume over the 7 days
   before day_index (entity_daily_vmi), project it forward 3 days, and compare to the actual
   cumulative_engagement_volume 3 days after day_index (skip events within the last 3 days of an
   entity's current tracked history — no actual to compare against yet). lift = (actual -
   projected) / max(projected, 1.0) — document in a comment that this is an interrupted-trend
   estimate against the entity's own pre-event trajectory, not a randomized control.

4. Aggregate per global_user_id: if they have >= 2 qualifying events, weight each event's lift by
   inverse variance (variance estimated from the entity's own residual volatility around its
   pre-event trend line) before averaging; with exactly 1 event, use it unweighted. Set confidence
   = HIGH if n_qualifying_events >= 3, else LOW.

5. Persist to user_causal_lift_scores: global_user_id, causal_lift_score, n_qualifying_events,
   confidence, last_computed_at. Upsert on global_user_id.

6. Wire into the existing MarketingEnrichmentScheduler cron, running after entity_daily_vmi/
   entity_daily_behavior_features are fresh, plus a POST /api/admin/run-causal-lift-scoring trigger.

7. Extend LanguageMarketingAPI's two existing endpoints (GET /api/marketing/language/{language}/users
   and .../movie/{movieName}/users) to LEFT JOIN user_causal_lift_scores and include
   causal_lift_score/n_qualifying_events/confidence (null if not yet computed for that user) in each
   returned user row — do not add a new parallel endpoint, this is an additive field on the
   existing, already-proxied response.

8. Tests: qualifying-event detection correctly uses each entity's own relative threshold, not a
   global one; inverse-variance weighting is applied only at n>=2; confidence gate is exactly
   n>=3 for HIGH; a user with zero qualifying events gets no row (not a zero score).
```

---

## F7 — Cross-movie non-obvious playbook miner (sequential pattern mining)

**Repo:** standalone Python batch job, same conventions as F4/F5
**Depends on:** F1 (outcome tiers), F2 (typed checkpoints as sequence symbols), F3 (regime-change
events as sequence symbols)
**Why this exists:** The direct answer to "what did other movies do to achieve maximum
viewership" — as an *ordered sequence* of actions/events, not a static bag of tactics, mined by
comparing what actually differs between top- and bottom-tier outcomes within the same market.

**Design notes**
- Build one symbolic sequence per entity: every `CheckpointType` event (F2, ordered by date) plus
  every `spilloverEvent` (F3) and every day a `netSentimentDelta` (F3) exceeded the same `1.5×`
  SPIKE threshold used elsewhere in this doc — merged and sorted by date into one ordered list of
  symbols per entity, e.g. `[CAST_ANNOUNCEMENT, TEASER, SPILLOVER_REDDIT, SENTIMENT_SPIKE, TRAILER,
  SPILLOVER_X, MUSIC_LAUNCH]`.
- Outcome tiers: within each `(industry, language)` cohort, top-tertile vs. bottom-tertile by F1's
  `cumulativeEV`-at-latest-tracked-day (cohort-relative, since absolute EV isn't comparable across
  markets of different size). Fall back to a global (non-cohort) split if a cohort has fewer than 6
  entities.
- Mine frequent subsequences of length 2–4 (PrefixSpan, via the `prefixspan` PyPI package)
  separately within the top-tier and bottom-tier sequence sets. For every subsequence that appears
  in both sets, run a **Fisher's exact test** on the 2×2 (appears/doesn't × top-tier/bottom-tier)
  contingency table.
- Same FDR correction (`q < 0.10`) and minimum-N gate (≥ 6 entities per tier) as F4/F5 — this is a
  repeated discipline across this doc's three Python jobs, not incidental.
- Persist to `playbook_patterns`: `pattern_sequence (jsonb ordered array), cohort, support_top_tier,
  support_bottom_tier, p_value, fdr_q_value, n_entities, computed_at`.

### Prompt
```
Write a standalone Python script (same conventions as F4/F5: shared `aura` DB, scheduled batch,
not part of either Java repo) that mines sequential audience-behavior/marketing-action patterns
distinguishing higher- from lower-viewership movies within the same market.

Depends on Checkpoint.checkpointType (F2), entity_daily_vmi (F1), entity_daily_behavior_features
(F3) already existing.

1. Build one ordered symbol sequence per managed_entities row of type MOVIE: merge (a) every
   Checkpoint's checkpointType (ordered by checkpoint_date), (b) every entity_daily_behavior_features
   row's non-null spillover_event as symbol "SPILLOVER_<platform>", and (c) every day_index where
   net_sentiment_delta implies the trailing ratio crossed 1.5x its own trailing-7-day average
   (reuse the same 1.5x SPIKE_MULTIPLIER value used in F3/SentimentAlertService) as symbol
   "SENTIMENT_SPIKE" — sort the merged list by underlying date/day_index.

2. Assign outcome tier per entity: within each (industry, language) cohort (query
   managed_entities), rank by latest cumulative_engagement_volume from entity_daily_vmi; top
   tertile = "high", bottom tertile = "low", middle third excluded from mining. If a cohort has
   fewer than 6 entities, pool all entities globally instead and print a note that this run used
   the pooled fallback.

3. Using the `prefixspan` package (pip install prefixspan), mine frequent subsequences of length
   2-4 separately from the "high" sequences and the "low" sequences (reasonable min-support, e.g.
   appears in at least 2 sequences within its tier — adjust and document your choice given actual
   data volume).

4. For every subsequence appearing in either mined set, build a 2x2 contingency table (appears in
   this entity's sequence: yes/no) x (tier: high/low) across ALL entities in scope (not just the
   ones it was mined from), and run scipy.stats.fisher_exact.

5. Apply Benjamini-Hochberg FDR correction (fdr_bh) across all subsequences tested in this run.
   Keep q < 0.10.

6. Persist to playbook_patterns (CREATE TABLE IF NOT EXISTS, delete-and-replace this run's cohort
   rows): pattern_sequence (jsonb ordered array of symbols), cohort, support_top_tier (count),
   support_bottom_tier (count), p_value, fdr_q_value, n_entities, computed_at.

7. requirements.txt (add prefixspan to F4/F5's list) + README section. Pytest coverage: a synthetic
   set of sequences with a known injected pattern difference between tiers is detected and survives
   FDR correction; the cohort-fallback triggers correctly under 6 entities; contingency-table
   construction correctly counts an entity that appears in neither mined set as a "no" in both
   tier columns.
```

---

## F8 — AuraService surfacing: proxies + entity-scoped endpoints

**Repo:** `AuraService`
**Depends on:** F1, F4, F5, F7 (F6 already wired in F6 itself, as an addition to an existing proxy)
**Design notes**
- Follow the exact `AuraMathMarketingProxyController` pattern (`forwardMarketingGet`, TTL cache,
  502-on-upstream-5xx) already established — these are new upstream routes on the *same* AuraMath
  service reading the *same* shared DB, not a new integration.
- Entity-scoped wrapper endpoints enforce ownership via `EntityAccessService`, same as every other
  entity-scoped controller in this codebase.

### Prompt
```
In AuraMath, add three read-only controller endpoints over the tables built in F1/F4/F5/F7 of this
doc (entity_daily_vmi, causal_precedence_edges, causal_precedence_chains, nonobvious_lever_findings,
playbook_patterns) — plain JdbcTemplate reads, same style as EntityMarketingService:

1. GET /api/marketing/entity/{entityId}/vmi -> the entity's entity_daily_vmi series (day_index,
   daily_engagement_volume, cohort_zscore, cumulative_engagement_volume) plus its peak day.

2. GET /api/marketing/entity/{entityId}/causal-chains -> resolve the entity's (industry, language)
   cohort, return the matching causal_precedence_chains rows for that cohort (or 'ALL' if no
   cohort-specific rows exist), each chain with its full edge list (from_series, to_series, lag,
   fdr_q_value, effect_size_r2, n_entities_supporting) — always include n_entities_supporting in
   the response so a caller can see how much evidence backs the chain, never hide it.

3. GET /api/marketing/entity/{entityId}/nonobvious-levers -> the pooled ('ALL' cohort)
   nonobvious_lever_findings rows, since F5 pools across cohorts given small N.

4. GET /api/marketing/playbook?industry=X&language=Y -> matching playbook_patterns rows for that
   cohort (or the pooled-fallback rows if that's what F7 produced for it), each with its full
   pattern_sequence and support/p-value/n_entities fields visible.

All four: if the relevant table has zero rows for this entity/cohort (analysis hasn't run yet, or
this entity doesn't have 14+/21+ days of history), return an explicit
{"status": "insufficient_history", "details": "..."} body with 200, not an empty 200 that looks
like "we looked and found nothing" — those are different facts and callers need to tell them apart.

Then in AuraService, add matching proxy methods to AuraMathProxyService (forwardGet + TtlCache,
same pattern as existing marketing proxies) and four entity-scoped endpoints:
GET /api/entities/{id}/vmi, /causal-chains, /nonobvious-levers, and a non-entity-scoped
GET /api/marketing/playbook?industry=&language= passthrough (this one isn't entity-specific).
Enforce ownership via EntityAccessService on the three entity-scoped ones, same as other
entity-scoped endpoints in this controller layer.

Tests (both repos): insufficient-history case returns the explicit status, not an empty array;
ownership enforced before returning cached results; cohort resolution falls back to 'ALL' correctly
when no cohort-specific chain/pattern rows exist. Mock interfaces only.
```

---

## F9 — Non-obvious action recommender integration

**Repo:** `AuraService`
**Depends on:** F8
**Why this exists:** Turns F5/F7's statistical findings into the same recommendation surface the
marketing team already uses, instead of a separate report nobody checks. All candidate generation
stays Java-computed with zero LLM involvement (matching `RecommendedActionCandidateServiceImpl`'s
existing, explicit "never LLM-scored" convention) — only the final phrasing pass touches an LLM,
and only to phrase/select, never to invent a number.

**Design notes**
- New candidate-generation methods on `RecommendedActionCandidateServiceImpl`, following its
  existing slug pattern: e.g. `nonobvious-lever-<featureName>` from F5 findings surviving FDR,
  `playbook-sequence-<cohort>` from F7 patterns matching the entity's cohort — each candidate
  carries its full statistical evidence (`pValue`, `fdrQValue`, `nEntities`, `direction`) as
  structured fields, not prose.
- Extend `llm.prompt.generate.recommended.actions`'s template with an explicit instruction: state
  findings factually from the supplied p-value/n/direction fields, never invent a number not
  present in the input, never claim more certainty than a `LOW`-confidence or small-`n` finding
  supports.

### Prompt
```
In AuraService, extend RecommendedActionCandidateServiceImpl with two new candidate-generation
methods reading the endpoints added in F8 of this doc (via the existing AuraMathProxyService
proxy methods, not a raw HTTP call) — follow the file's existing all-Java, zero-LLM candidate
pattern exactly (see its class javadoc).

1. generateNonObviousLeverCandidates(entityId): call the F8 nonobvious-levers proxy, and for each
   finding with fdr_q_value < 0.10, emit a candidate with slug "nonobvious-lever-<featureName>"
   carrying structured fields: featureName, direction, pValue, fdrQValue, nEntities — no prose, the
   existing RecommendedActionsService LLM pass phrases it later. Skip findings above the q<0.10 bar
   entirely (RecommendedActionCandidateServiceImpl's existing methods already have a "don't surface
   what didn't clear a threshold" convention — follow it).

2. generatePlaybookCandidates(entityId): resolve the entity's (industry, language), call the F8
   playbook endpoint for that cohort, and for each pattern with fdr_q_value < 0.10, emit a candidate
   "playbook-sequence-<cohort>" carrying: patternSequence (ordered list), supportTopTier,
   supportBottomTier, fdrQValue, nEntities.

3. Register both new candidate generators in whatever central list/dispatch
   RecommendedActionCandidateServiceImpl already uses to assemble the full candidate set (check the
   existing 12 slugs' wiring and match it).

4. Extend the llm.prompt.generate.recommended.actions template (application.properties) with an
   explicit instruction block: "Some candidates include statistical evidence (p-value, FDR q-value,
   sample size, direction). State these findings factually using only the numbers provided — never
   invent a number, percentage, or confidence level not present in the input. If a finding's sample
   size (nEntities) is small, say so plainly rather than implying strong confidence." Show me the
   current template first so this reads as a natural extension, not a bolted-on paragraph.

5. Tests: a finding with fdr_q_value >= 0.10 never produces a candidate; candidate fields carry the
   exact numeric values from the proxy response, unmodified; the LLM prompt-construction step never
   passes anything to the LLM that isn't already present in a candidate's structured fields (i.e.
   the Java layer doesn't compute new numbers just for the prompt). Mock interfaces only.
```

---

## F10 — Momentum & Causal Chain report

**Repo:** `AuraService`
**Depends on:** F1, F4, F6 (via F8's enriched language-user data), F7, F9
**Design notes**
- Reuse the `EntityMarketingReportService`/`EntityMarketingReportPdfService` pattern, same as the
  predictive doc's F10 — but this is a *different* report (post-release momentum/causal, not
  pre-release factor/prediction), so give it its own service/endpoint rather than merging into that
  one.
- Every section must degrade to an explicit "not enough tracked history yet" placeholder — given
  this whole doc's minimum-N gates, a freshly-added entity will legitimately have empty F4/F5/F7
  sections for weeks, and that must read as "too early," not "broken" or "nothing found."

### Prompt
```
In AuraService, add a "Momentum & Causal Chain" report — distinct from
PREDICTIVE_LAUNCH_FEATURE_BREAKDOWN.md's separate "Launch Plan" report (that one is pre-release
prediction; this one is post-release audience-behavior pattern analysis) — reusing the
EntityMarketingReportService/EntityMarketingReportPdfService pattern (don't build a new report
pipeline from scratch).

1. Add MomentumCausalReportService.buildReport(entityId) assembling, via the F8 proxy endpoints:
   - vmiTrend: from GET /api/entities/{id}/vmi (F1) — the daily series plus peak day.
   - causalChains: from GET /api/entities/{id}/causal-chains (F4), each with its full evidence
     (lag, fdr_q_value, effect_size_r2, n_entities_supporting) shown, not summarized away.
   - topCausalLiftUsers: from the F6-enriched language-user endpoint, filtered/sorted by
     causal_lift_score descending, confidence=HIGH entries first, then LOW clearly labeled as such.
   - nonObviousLevers / playbookMatches: from F9's now-generated recommended-action candidates
     (reuse those, don't re-fetch F8 endpoints directly a second time).
   Each section must render an explicit "insufficient tracked history yet" placeholder (matching
   the {"status":"insufficient_history"} shape F8 returns) instead of an empty section or an error,
   if the entity doesn't have enough days/data for that analysis yet.

2. Add GET /api/entities/{id}/momentum-report (JSON) and
   GET /api/entities/{id}/momentum-report/pdf (reuse the existing OpenPDF rendering approach from
   EntityMarketingReportPdfService).

3. Enforce entity ownership via EntityAccessService, same as every other entity-scoped endpoint.

4. Tests: a fully-populated entity (all F1-F9 tables have rows for it) produces all four sections;
   a freshly-tracked entity with only a few days of history gets clear insufficient-history
   placeholders in every section, not a 500 or an empty-looking report. Mock interfaces only.
```

---

## Suggested build order

F1 → F2 (parallel to F1) → F3 → F4/F5/F6/F7 (all four can run in parallel once F1–F3 are done —
no dependency among them) → F8 → F9 → F10. The three Python batch jobs (F4, F5, F7) are entirely
independent of each other and of F6, and can be developed/tested with synthetic data before any
real entity has enough tracked history to produce non-trivial output — build them early even though
their *findings* won't be meaningful for the first few weeks of real data collection.
