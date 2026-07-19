# Predictive Factor Data Audit

Answers two questions for each of the 100 factors: **(1) is this factor even usable to predict
a movie's performance before/at release**, and **(2) does the shared `aura` Postgres DB actually
have — or let us derive — the data behind it**. Grounded in live queries against the DB
(`psql -U mukundv -d aura -h localhost`), not assumptions. See
`PREDICTIVE_LAUNCH_FEATURE_BREAKDOWN.md` for the feature-build plan this audit feeds into.

## Predictive vs. post-hoc — the filter applied to every factor

A factor is **predictive** only if its value can be known or reasonably estimated *before the
outcome it's supposed to predict* — i.e., before/at release, from the script, cast, production,
marketing plan, calendar, or comps history. A factor whose value only exists *because* the movie
already released and got a reaction (word-of-mouth, review aggregator scores collected after
release, social buzz, repeat viewership) is an **outcome/monitoring signal, not a predictor** —
using it to "predict" the same movie's performance is circular. Those factors are still valuable
(they're exactly what `MentionService`/`SentimentAlertService`/`TopSpreaderLookupService` already
track), just not as launch-prediction inputs.

## Database ground truth (live counts, run 2026-07-17)

| Table | Rows | Notes |
|---|---|---|
| `movies_data_collection` | 636,504 total / 519,623 post-2000 | **Mostly a global TMDB-style dump** — English (241,696), French, Japanese, Spanish, German dominate. Indian-language rows post-2000: Hindi 3,740, Tamil 2,778, Malayalam 2,230, Telugu 1,916, Kannada 980 (**11,644 total** — this is the actual product domain). |
| `actors_data_collection` | 62,413 | PK `(actor_name, movie_name, release_date)`. `language` casing is inconsistent (`hindi` vs `Hindi` — 20,102 vs 13,283 rows) — an exact-match join needs case-folding. |
| `managed_entities` | 38 | The app's actual tracked projects; 24 have a synopsis. Only 32 name-match anything in `movies_data_collection` (expected — these are mostly *upcoming* films, not yet comps). |
| `mentions` / `x_posts` / `youtube_comments` / `reddit_posts` / `instagram_posts` / `sentiment_alerts` | 32,891 / 59,766 / 52,538 / 367 / 70 / 105 | Real, usable — but these are **post-release monitoring data**, per the filter above. |
| `release_calendar_events`, `planned_releases` (proposed in the build-plan doc) | **do not exist** | Not in `\dt`. Calendar/clash factors have no dedicated table yet. |

Coverage of key `movies_data_collection` columns, **restricted to the 11,644 Indian-language
post-2000 rows** (the numbers that actually matter for this product, not the global TMDB noise):

| Column | Non-empty | % |
|---|---|---|
| `budget` | 1,620 (Hindi 802, Tamil 252, Malayalam 198, Telugu 220, Kannada 128) | 14% |
| `revenue` | 1,470 | 13% |
| `synopsis` | 2,614 (Hindi 1,191, Tamil 571, Malayalam 412, Telugu 367, Kannada 73) | 22% |
| `conflict_balance_score` / `narrative_novelty_score` | 175 (Hindi 109+11, Telugu 25, Tamil 22, Malayalam 8) | 1.5% — only backfilled where synopsis + an LLM pass already ran |
| `imdb_rating` | ~1,313 (global figure; Indian subset smaller) | low |
| `rating_10` | high (188,221 globally) | good, but this is a **post-release** aggregator score |
| `release_day` (day-of-week) | 11,644 | **100%** |
| `production_companies` | 6,619 | 57% |
| `genre` / `runtime` | majority | good |
| `trailer_release_date` / `trailer_views` | 18 (global) / ~0 for Indian rows | effectively **absent** for this product's actual films, despite the columns existing |
| `teaser_release_date` / `first_song_release_date` | 5 / 2 (global) | effectively **absent** |
| `release_event_type/name/detail` (holiday/festival tagging) | 5 total | effectively **absent** |
| `gdp_usd_billions` / `inflation_rate_pct` | 467,127 (mostly the global rows) | present but macro, low marginal value |
| Movies with **both** `budget` and `revenue` populated | 10,624 (global) | this is the actual comps pool for any $ prediction |
| Distinct actors joinable to a movie with budget+revenue | 1,077 | small but real, usable for talent-value regression |

One thing the build-plan doc got wrong by assumption: it treated the YouTube promo-metrics
table as a confirmed, populated source. It isn't a separate table — the columns
(`trailer_release_date`, `trailer_views`, etc.) live directly on `movies_data_collection`, but
they're populated for only a handful of rows total and **zero** of the Indian-language rows
checked. Also, `movies_data_collection` already contains **future/announced release dates**
(rows dated into 2026), which means direct-clash detection (factor 62) is derivable *today* by
self-joining on `release_date` — no new crawler needed for that one factor specifically.

## Status legend (used below)

- **HAVE** — real column(s), non-trivial coverage today, usable as-is (coverage caveat noted).
- **DERIVABLE** — not stored directly, but computable *now* from existing columns: LLM-over-`synopsis`
  (same pattern as `ConflictBalanceServiceImpl`), aggregation over `actors_data_collection`/
  `movies_data_collection` history, or date arithmetic on `release_date`.
- **SPARSE** — the column exists but coverage is too thin today to trust (cited above).
- **ABSENT** — nothing in the schema; requires new crawling (a realistic free/public source exists or is proposed in the build-plan doc).
- **MANUAL** — nothing in the schema, and no reliable free/public source exists either (private deal
  data, or a domain — exams, elections, censorship — where a crawler would be guessing). The only
  honest path is a Production House data-entry form, written with `source=MANUAL` into
  `entity_factor_scores` (see F1's design in the build-plan doc). Distinct from ABSENT: ABSENT means
  "needs a crawler we haven't built yet," MANUAL means "no crawler should be built for this."

---

## Category 1 — Narrative & Screenplay (1–15): predictive, but only 3 hold up on synopsis alone

**Predictive: yes, all 15.** Every one of these is a property of the finished script, knowable
well before release. **DB status: mixed, revised from an earlier "DERIVABLE for all 15" pass.**
A `synopsis` is 2-4 sentences of plot summary. That's enough to judge **premise-level** factors —
does a twist/subversion exist, is there a stated romance or comedic track — but not **execution-level**
factors, which describe *how well something is written and paced*, not *whether it exists*. A
synopsis cannot show pacing across acts, tonal shifts, dialogue quality, or plot-hole density,
because it doesn't contain scenes, dialogue, or act structure. Scoring those 11 factors from
synopsis alone (as `ConflictBalanceServiceImpl`'s pattern currently does for 1–2) produces a
plausible-looking number with no real signal behind it — the LLM is guessing from a one-line
premise, not reading a script.

Only 1, 2, 4, and 15 are premise-level and hold up on synopsis alone. The other 11 need the actual
screenplay — or at minimum a scene-by-scene treatment/outline — as LLM input; synopsis-only scores
for those should be labeled low-confidence, not treated as equivalent to the two already-computed
factors. `ManagedEntity` has no field for this today (only `synopsis`); F1 needs a `screenplayText`/
`detailedTreatment` input before these 11 can be scored for real, on top of the existing `synopsis`
coverage gap (22% of Indian-language post-2000 rows).

| # | Factor | Predictive | DB status |
|---|---|---|---|
| 1 | Protagonist-Antagonist Conflict Balance | Yes | **HAVE** — already computed (`conflict_balance_score`), 175 Indian rows scored |
| 2 | High-Concept Narrative Novelty | Yes | **HAVE** — already computed (`narrative_novelty_score`, `_v2`), same 175 rows |
| 3 | Screenplay Pacing and Rhythm | Yes | **NEEDS FULL SCRIPT** — pacing is an across-scenes property; synopsis has no scene structure to judge it from |
| 4 | Genre Template Adherence vs. Subversion | Yes | DERIVABLE from synopsis + `genre` column — premise-level, holds up on synopsis alone |
| 5 | Emotional Climax Payoff | Yes | **NEEDS FULL SCRIPT** — synopsis can state *that* a climax happens, not whether its build-up and execution land |
| 6 | Dialogue Punch / Catchphrases | Yes (weakly) | **NEEDS FULL SCRIPT** — requires actual lines, which synopsis never contains |
| 7 | Tonal Consistency across Halves | Yes | **NEEDS FULL SCRIPT** — a shift between halves can't be seen in a single-paragraph summary |
| 8 | Subtext vs. Preachiness | Yes | **NEEDS FULL SCRIPT** — subtlety-vs-heavy-handedness is a scene/dialogue judgment, not a plot-summary one |
| 9 | Logic Gaps and Plot Holes | Yes | **NEEDS FULL SCRIPT** — a synopsis compresses away the very details plot holes live in |
| 10 | Ensemble Cast Cohesion | Yes | **NEEDS FULL SCRIPT** — actor-count on `ManagedEntity` is a weak proxy; real cohesion needs to see interaction across scenes |
| 11 | Romantic Track Integration | Yes | **NEEDS FULL SCRIPT** for real confidence — synopsis can flag *that* a romance exists, not how well it's woven in |
| 12 | Comedic Track Cohesion | Yes | **NEEDS FULL SCRIPT** — synopsis + `genre` can flag "this is a comedy," not whether the comedic track coheres |
| 13 | Realism vs. Melodrama | Yes | **NEEDS FULL SCRIPT** — a tonal-register judgment that needs actual scene writing, not a plot summary |
| 14 | Flashback Relevance/Pacing | Yes | **NEEDS FULL SCRIPT** — synopses almost never describe flashback structure at all |
| 15 | Twist Effectiveness | Yes | DERIVABLE from synopsis via LLM — premise-level, holds up on synopsis alone |

---

## Category 2 — Cast Capital & Controversies (16–30)

**Predictive: mostly yes** (star casting, director, chemistry are all fixed pre-release); a few
are only knowable *during* the promo window, not at greenlight (24, 29 depend on speeches that
haven't happened yet), and none are truly post-hoc.

| # | Factor | Predictive | DB status |
|---|---|---|---|
| 16 | Star-to-Character Persona Fit | Yes | ABSENT — no persona/image data anywhere; would stay LLM-qualitative on `character_name` + synopsis at best, no ground truth to calibrate against |
| 17 | Core Fanbase Mobilization Value | Yes | DERIVABLE (proxy) — `actors_data_collection` gives an actor's historical opening-weekend pattern *only where `budget`/`revenue` also populated* (1,077 actors) |
| 18 | Lead Actor Screen Chemistry | Yes | ABSENT — no pairing-outcome data; would need a `movies_data_collection` self-join on repeat actor *pairs* and their comps' `revenue`, which is derivable but the underlying revenue coverage (13%) makes it noisy |
| 19 | Support Cast Performance Credibility | Yes | DERIVABLE (weak) — `role_position`/`character_name` + historical `rating` per supporting actor |
| 20 | Directorial Brand Equity | Yes | **DERIVABLE** — votes-weighted average `rating` across a director's past `actors_data_collection` rows (this is exactly F8 in the build-plan doc) |
| 21 | Anti-Hero Appeal / Moral Ambiguity | Yes | DERIVABLE from synopsis via LLM |
| 22 | Off-Screen Actor Controversy | Yes (during promo window) | DERIVABLE from `mentions`/`x_posts`/etc. matched on actor name — but only useful once controversy-monitoring is already running (F14 in the build plan); nothing pre-dated |
| 23 | Star Satiation / Overexposure | Yes | **DERIVABLE** — count of an actor's `actors_data_collection` rows with `release_date` in the trailing 12 months of the target's own `release_date`, pure arithmetic |
| 24 | Off-Script Event Speech Impact | Partial | ABSENT until it happens — inherently only knowable once the promo tour starts, not at planning time |
| 25 | Lead Actor Vulnerability and Range | Yes | ABSENT — no performance-quality data; LLM-on-synopsis can at best guess from the *character's* arc, not the actor's delivery |
| 26 | Multi-Generational Appeal | Yes | ABSENT — no demographic-reach data on actors anywhere in schema |
| 27 | Miscasting / Role Incongruence | Yes | ABSENT — same gap as 16 |
| 28 | Nostalgic Screen Reunions | Yes | **DERIVABLE** — self-join `actors_data_collection` for the same actor-pair appearing together in an earlier movie, then measure the gap in `release_date` |
| 29 | Star Political Aspirations / Dialogue Placement | Partial | ABSENT until the speech/dialogue happens |
| 30 | Cameo Appearances of Iconic Stars | Yes | DERIVABLE only if cameos are named in synopsis/cast list; `role_position` could flag likely cameos (very low position, minimal `character_name`) but this is a weak heuristic |

---

## Category 3 — Production Scale & Technical (31–45)

**Predictive: yes in principle** (all are properties of the finished/near-finished film, knowable
before wide release) — **but DB status: essentially all ABSENT.** There is no VFX, sound,
choreography, cinematography, editing, or set-design metadata anywhere in the schema, and no
video/audio asset to run ML against (trailer URLs aren't stored, only sparse view/comment counts).
This entire category would need a new video/audio-analysis pipeline against actual trailer
footage once one exists — a text-only synopsis cannot stand in for "is the CGI good."

| # | Factor | DB status |
|---|---|---|
| 31 | VFX Technical Quality | ABSENT — no asset to analyze |
| 32 | Sound Design/Mixing | ABSENT |
| 33 | Action Choreography Innovation | ABSENT (synopsis can flag "action-heavy" genre at best) |
| 34 | Background Score (BGM) Impact | ABSENT — no audio asset or music-director data (confirmed gap in build-plan doc; `ManagedEntity` has no `musicDirector` field) |
| 35 | Production Design/Scale | DERIVABLE (very weak proxy) — `budget` where present, nothing else |
| 36 | Cinematography/Color Grading | ABSENT |
| 37 | Excessive Runtime | **HAVE** — `runtime` column, 62% coverage globally, good for Indian rows too; pure threshold check (>160 min), no LLM needed |
| 38 | Dynamic Editing/Transition Pacing | ABSENT |
| 39 | Period/Cultural Setting Authenticity | DERIVABLE (weak) — synopsis via LLM, only if the synopsis actually states a time/setting |
| 40 | Budget-to-Scale Efficiency | DERIVABLE — `budget` vs. `revenue`/`production_companies` scale, only where both populated (14%/13% coverage) |
| 41 | Animation for Flashbacks | ABSENT |
| 42 | Intrusive Song Placement | DERIVABLE (weak) — synopsis rarely mentions song placement; `first_song_release_date` too sparse |
| 43 | Location Novelty | ABSENT |
| 44 | Live Action vs. Green-Screen | ABSENT |
| 45 | Graphic Violence | DERIVABLE (weak) — synopsis + `genre` via LLM |

**Bottom line for Category 3: 1 of 15 (`runtime`) is real data; the rest need a new data source
(trailer/footage ML pipeline) that doesn't exist in this system today.**

---

## Category 4 — Marketing & Promotion (46–60)

**Predictive: yes for most** — these describe the marketing *plan*, decided before release.
**DB status: the columns exist on `movies_data_collection` (`trailer_release_date`,
`trailer_days_to_release`, `trailer_views`, teaser/song equivalents) but are populated for
essentially none of the Indian-language rows (0–18 out of 11,644 globally, effectively 0 for the
actual product languages).** So this category is schema-ready but data-empty — a crawler (F5 in
the build plan) would populate it, not new columns.

| # | Factor | Predictive | DB status |
|---|---|---|---|
| 46 | Teaser/Trailer Impact | Yes | SPARSE — `trailer_views`/`teaser_views` columns exist, ~0 populated for Indian rows |
| 47 | Timing of First Single | Yes | SPARSE — `song_days_to_release` exists, ~0 populated |
| 48 | Brand Extensions / Sequel Names | Yes | DERIVABLE — string-match `movie_name` against prior titles in the same `movie_name` family (sequel numbering, franchise keywords) |
| 49 | Viral Music/Social Audio Trends | Yes | ABSENT — no social-audio-trend table |
| 50 | Pre-Release Promotional Controversies | Yes | DERIVABLE once F14-style talent monitoring runs on `mentions`/`x_posts`, but nothing pre-populated |
| 51 | Star Attendance at Events | Yes | ABSENT |
| 52 | Micro-Video Social Campaigns | Yes | ABSENT |
| 53 | Influencer-Driven Promotions | Yes | ABSENT — no `creator_directory`-type table exists (proposed as F6 in the build plan, not built) |
| 54 | Misleading Trailer Marketing | Yes (only near release) | ABSENT — would need trailer content vs. final film comparison |
| 55 | HD Promo/BTS Content | Yes | ABSENT |
| 56 | Countdown Posters | Yes | ABSENT |
| 57 | Over-Saturated Marketing | Yes | ABSENT — no spend/frequency tracking |
| 58 | Cross-Promotion/Brand Partnerships | Yes | ABSENT |
| 59 | Dynamic Ticket Pricing | Yes (very late signal) | ABSENT — no ticketing data anywhere (explicitly out of scope per the build-plan doc — no free BookMyShow/District API) |
| 60 | Global Promotional Tours | Yes | ABSENT |

**Bottom line: 2 of 15 have a column with any data at all (46, 47), and both are effectively empty
for Indian releases. Everything else needs new crawling.**

---

## Category 5 — Calendar & Market Dynamics (61–70)

**Predictive: yes, all 10** — release date is chosen months in advance, and clash/holiday/exam/
election/sports calendars are public knowledge ahead of time. **DB status: no dedicated calendar
table exists**, but two of the ten are derivable *right now* from `movies_data_collection` alone:

| # | Factor | Predictive | DB status |
|---|---|---|---|
| 61 | Holiday Release Windows | Yes | ABSENT — `release_event_type/name/detail` columns exist but only 5 rows populated total; needs an external holiday calendar (F2 in build plan) |
| 62 | Direct Box Office Clashes | Yes | **DERIVABLE today** — self-join `movies_data_collection` on `release_date` (+ same `language`/industry); confirmed working, and the table already has rows dated into 2026, so upcoming clashes are visible now, no new crawler needed |
| 63 | Student Exam Schedules | Yes | **MANUAL** — no reliable free exam-calendar source exists; Production House enters known regional exam windows rather than a crawler guessing at them |
| 64 | Political Events/Elections | Yes | **MANUAL** — same reasoning; ECI dates are announced piecemeal and a curated seed is itself a manual process, so route it through Production House entry instead of pretending it's a crawler |
| 65 | Major Sporting Events (IPL) | Yes | **MANUAL** — free cricket-fixture feeds are rate-limited/unreliable enough that direct entry (a known, short annual calendar) is more trustworthy than a crawl |
| 66 | Summer Vacation Windows | Yes | **DERIVABLE** — pure calendar math on `release_date` (fixed academic-calendar months), no external data needed |
| 67 | Extreme Weather | Yes | **MANUAL** — no weather data source exists in this system, and weather-at-a-future-release-date is a forecast, not a fact; Production House can flag known monsoon/heat-wave windows regionally rather than this being fabricated |
| 68 | Theatrical Window / OTT Strategy | Yes | DERIVABLE (partial) — `streaming_platform`/`status` columns on `actors_data_collection` hint at OTT release but aren't reliably dated |
| 69 | Post-Clash Spillover Audience | Yes | DERIVABLE once 62 is computed — same self-join, opposite sign |
| 70 | Re-Release Timing/Nostalgia | Yes (edge case) | DERIVABLE — a movie appearing twice in `movies_data_collection` under different `release_date`s |

`release_day` (day-of-week) is 100%-populated for all 11,644 Indian rows, which is a free input
day-of-week seasonality models can use immediately, independent of the gaps above.

---

## Category 6 — Legal, Censorship & Administrative (71–80)

**Predictive: yes for all 10** (certification, bans, disputes are all resolved or at least known
before release). **DB status: entirely MANUAL** — no CBFC rating column, no ban/dispute/tax-exemption
table anywhere in the schema, and this is the single most data-poor category. A CBFC public
certificate-search crawl (noted as a future option in the build-plan doc) could eventually cover
the certification piece (71-ish), but bans, disputes, and community-sensitivity flags are events,
not a queryable database anywhere public — the only honest path today is Production House manual
entry for all 10, with a CBFC crawler as a later upgrade for the subset it can actually reach.

| # 71–80 | all ten factors | **MANUAL** — Production House entry; no derivation path from current data, and no full-category crawl target exists |

---

## Category 7 — Financial Controls & Distribution (81–90)

**Predictive: mixed.** Budget and revenue-band are knowable pre-release and useful; deal-structure
factors (MG terms, revenue splits, financing interest rates, producer solvency) are private
business data this system was never built to see, and most only *matter* at/after release (KDM
lockout, screen-count allocation are release-week operational events, not pre-release predictors).

| # | Factor | Predictive | DB status |
|---|---|---|---|
| 81 | KDM Lockout | No (release-morning event) | ABSENT — also not a *predictor*, it's a release-day failure mode; not a manual-entry candidate either since it isn't known ahead of release |
| 82 | Minimum Guarantee Distribution | Yes | **MANUAL** — private deal terms only the Production House itself knows; no public source will ever have this |
| 83 | Outright Territorial Sales | Yes | **MANUAL** — same, private deal data |
| 84 | Film-Finance Interest Rates | Yes | **MANUAL** — same, private financing terms |
| 85 | Multiplex Revenue Share Splits | Yes | **MANUAL** — negotiated per-film/per-chain, not published anywhere |
| 86 | Subtitle/Dubbing Quality | Yes | **MANUAL** — no localization-quality signal anywhere; Production House/QC team is the only source pre-release |
| 87 | Screen Count Allocation | Partial (decided close to release) | **MANUAL** — exhibitor allocations aren't public ahead of release; Production House can log confirmed counts as they're finalized |
| 88 | P&A Commitments | Yes | DERIVABLE (very weak) — `production_companies` presence/count as a scale proxy only; actual spend figures are **MANUAL** (private) if more precision is wanted |
| 89 | Joint Production Partnerships | Yes | **DERIVABLE** — `production_companies` field, 57% coverage on Indian rows, count/identify multi-studio co-productions directly |
| 90 | Producer Debt/Studio Solvency | Yes | **MANUAL** — no financial-health data anywhere public; this is exactly the kind of fact only the Production House would disclose, if at all |

`budget`/`revenue` (14%/13% coverage on Indian rows, 10,624 movies globally with both) support a
budget-band comps model even though the deal-structure factors above them don't.

---

## Category 8 — Post-Release Dynamics (91–100)

**Predictive: no, for all 10 — by definition.** Every factor in this category is an *outcome* of
release (word-of-mouth, review aggregation, social discourse, repeat viewership, booking
velocity), not an input available beforehand. Using them to "predict" the same film's performance
is circular; their real job is post-release monitoring, which this system already does via
`MentionService`/`SentimentAlertService`/`TopSpreaderLookupService`/`mentions`/`x_posts`/
`youtube_comments`/`reddit_posts`/`instagram_posts` (32,891 / 59,766 / 52,538 / 367 / 70 rows —
real, usable data, just not for the "before it happens" question this audit is scoped to).

The one legitimate predictive use of this category: **factors 91–100 measured on a *comp* movie
become training signal for predicting a *different, upcoming* movie** — e.g., `rating_10` (188,221
rows globally) on past comps feeds the same director/genre/industry baseline used elsewhere. That's
not "factor 94 predicts itself," it's ordinary comps modeling, already covered under Category 1/2's
DERIVABLE entries.

| # 91–100 | all ten factors | **Not usable as pre-release predictive inputs.** Data exists (`mentions`, `x_posts`, `rating_10`, etc.) but only becomes available *after* the outcome it would be predicting. |

---

## Summary

| Category | Predictive factors | DB-ready today (HAVE) | Derivable now, no new input | Needs full script text | Manual entry (Production House) | Needs new crawler |
|---|---|---|---|---|---|---|
| 1. Narrative (1–15) | 15 / 15 | 2 (already computed: 1, 2) | 2 (4, 15 — premise-level, synopsis alone suffices) | **11** (3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14) — not a crawl gap, a missing `screenplayText`/treatment input | 0 | 0 |
| 2. Cast Capital (16–30) | 13 / 15 (24, 29 only mid-campaign) | 0 | 4 (17, 20, 23, 28 — pure arithmetic over `actors_data_collection`) | 0 | 0 | 9 (16, 18, 19, 25, 26, 27, 30 weak-to-absent; 22/50 need monitoring already running) |
| 3. Production/Technical (31–45) | 15 / 15 | 1 (`runtime`) | 3 weak proxies (35, 40, 42/45 via synopsis) | 0 | 0 | 11 — needs a video/audio pipeline against real trailer assets |
| 4. Marketing (46–60) | 15 / 15 | 0 | 1 (48, string-match on title) | 0 | 0 | 14 — columns exist for 46/47 but are empty; rest need new crawlers (F5–F7, F15/16 in build plan) |
| 5. Calendar (61–70) | 10 / 10 | 1 (`release_day`, 100% coverage) | 5 (62 clash, 66 summer, 68 OTT-window, 69 spillover, 70 re-release — pure date math / existing columns) | 0 | **4** (63, 64, 65, 67 — no reliable free source; Production House enters known windows) | 1 (61 — free holiday API, F2 in build plan) |
| 6. Legal/Censorship (71–80) | 10 / 10 | 0 | 0 | 0 | **10** — no reliable free/public source for any of it | 0 (a CBFC crawl is a possible future upgrade for the certification piece only, not a near-term plan) |
| 7. Financial (81–90) | 8 / 10 (81, 87 are release-week events, weak-to-no value as pre-greenlight predictors) | 0 | 2 (89 via `production_companies`; 88 weak proxy) | 0 | **7** (82, 83, 84, 85, 86, 87, 90 — private deal/financial terms only the Production House knows) | 0 |
| 8. Post-Release (91–100) | 0 / 10 | — | — | — | — | out of scope by definition — this is outcome data, already handled by the monitoring stack |

**Overall: of the ~90 genuinely predictive factors (excluding Category 8), roughly 4 are already
computed and stored, ~15 more are derivable today with zero new input (pure arithmetic over
`actors_data_collection`/`movies_data_collection`, date math, or synopsis-sufficient LLM
judgment), 11 need a new `screenplayText` input before their existing LLM-scoring pattern can be
trusted, ~21 need Production House manual entry because no free/public source will ever cover them
(private deal terms, exam/election/weather calendars, legal/censorship status), and the remaining
~39 — most of Categories 3 and 4 — need new crawlers or a video/audio pipeline that doesn't exist
in this system yet.** The two highest-leverage gaps are: `synopsis`/`screenplayText` coverage for
Category 1 (22% synopsis coverage today, and no script-text field exists at all), and building the
manual-entry surface (`source=MANUAL` in `entity_factor_scores`) that the 21 Production-House-only
factors need — both are product/data-entry problems, not capability problems.
