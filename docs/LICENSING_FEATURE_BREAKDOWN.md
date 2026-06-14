# AuraService — Licensing & Multi-Tenancy Feature Breakdown

A build plan for the 18 licensing/access-control requirements, decomposed into **8 sequenced
features**. Each feature has: scope, the requirements it covers, design notes grounded in the
current codebase, and a ready-to-paste **Claude Code prompt**.

> **Build order matters.** Features F1 → F8 are ordered by dependency. F1 (entity ownership) is
> foundational; F3 (license model) is the backbone for F4–F8. Build and merge them in order.

## Codebase facts these prompts assume

- Spring Boot 3.2 / Java 17, Spring Security 6 + JWT, PostgreSQL, Hibernate `ddl-auto=update` (no Flyway).
- `User` entity: `id, username, password, role (ROLE_USER|ROLE_ADMIN), alertWebhookUrl, timezone`.
- `ManagedEntity` (table `managed_entities`) is currently **global** — no owner column. Keywords are
  an embedded `List<EntityKeyword>` collection table `entity_keywords`.
- `Mention` (table `mentions`) — ManyToOne to `ManagedEntity`, `platform`, `postDate`, etc.
- `UserEntityView` (table `user_entity_views`) tracks *views*, not ownership.
- Security: `JwtAuthenticationFilter`, `SecurityConfig`, `JpaUserDetailsService`. Auth principal = username.
- Controllers return `ResponseEntity<DTO>`; DTOs live in `dto/`. Audit via `AuditLogFilter`.
- **No** License/Plan/Tier/Subscription model exists yet.

---

## F1 — Per-user entity ownership (data isolation)

**Covers requirement:** 1

**Why first:** Every other rule ("across all entities", "max N entities", admin sees all) depends on
knowing *which user owns which entity*. Today entities are global.

**Design notes**
- Add `owner` (ManyToOne `User`, column `owner_id`) to `ManagedEntity`. Backfill existing rows to a
  default admin/seed user via a one-time startup step (since there's no Flyway).
- On create, stamp `owner` = current authenticated user.
- All read/list/update/delete in `EntityController`/`EntityService` must be scoped to the owner.
- Anything that reaches entities transitively (dashboard, mentions, checkpoints, crisis, marketing
  aggregation) must also enforce ownership so a user can't read another user's entity by ID.

### Prompt
```
In the AuraService Spring Boot app, managed entities are currently global/shared. I need them
owned by the user who created them, so each user only sees and acts on their own entities.

1. Add an `owner` field to ManagedEntity (ManyToOne User, column `owner_id`, not nullable).
   Update the managed_entities DDL doc in README to include owner_id.
2. On entity creation (EntityService/EntityController POST /api/entities/{entityType}), set owner
   to the currently authenticated user (resolve from SecurityContext username via the user repository).
3. Scope ALL entity reads/lists/updates/deletes to the current user: list returns only entities
   the user owns; get/update/delete by id must 404 (not 403, to avoid leaking existence) if the
   entity isn't owned by the caller.
4. Audit every other place that loads an entity by id and ensure ownership is enforced before
   returning data: DashboardController/Service, MentionController/MentionActionController,
   CheckpointController, CrisisController, MarketingAggregationController, AnalyticsController,
   EntityMarketingReportController. Add a shared guard (e.g. an EntityAccessService method
   `assertOwnedByCurrentUser(entityId)`) and reuse it everywhere rather than duplicating logic.
5. Because ddl-auto=update with no Flyway, add an idempotent startup backfill (ApplicationRunner)
   that assigns any managed_entities row with null owner_id to the seeded admin user.
6. Add tests: a user cannot read/update/delete another user's entity; listing is owner-scoped.
   Mock interfaces, not concrete classes (Java mocking constraint in this repo).
```

---

## F2 — Admin cross-user access with user selector

**Covers requirement:** 2

**Depends on:** F1

**Design notes**
- `ROLE_ADMIN` bypasses the ownership filter and may pass an optional `?asUserId=` (or `?ownerId=`)
  param to scope the view to one user's entities.
- Build on the existing `EntityAccessService` guard from F1: admins skip the owner check; when
  `asUserId` is supplied, admins are scoped to that user.
- Add an admin-only endpoint to list selectable users.

### Prompt
```
Building on per-user entity ownership, give ROLE_ADMIN users access to ALL entities plus the
ability to scope the view to a specific user's entities.

1. In EntityAccessService (the ownership guard), if the current user has ROLE_ADMIN, bypass the
   owner restriction.
2. Add an optional query param `ownerId` to the entity list endpoint and to dashboard/mention
   list endpoints. For admins, `ownerId` scopes results to that user's entities; if omitted, the
   admin sees all entities. Non-admins must get 403 if they pass `ownerId`.
3. Add an admin-only endpoint `GET /api/admin/users` (ROLE_ADMIN, enforced in SecurityConfig and/or
   @PreAuthorize) returning id + username for populating a user-selector dropdown in the UI.
4. Make sure admin access is also honored in the transitive entity guards added previously
   (dashboard, mentions, checkpoints, crisis, marketing, analytics, reports).
5. Tests: admin sees all entities; admin with ownerId sees only that user's; non-admin passing
   ownerId is rejected; non-admin behavior from F1 is unchanged. Mock interfaces, not concrete classes.
```

---

## F3 — License model, tiers, and price catalog

**Covers requirements:** 3, 9, 13, 14

**Depends on:** F1

**Design notes**
- New `LicenseTier` enum: `BRONZE, SILVER, GOLD, DIAMOND`. Encode the per-tier limits as enum
  fields so F4–F8 read from one source of truth:

  | Tier | Keywords | Entities | Mentions/mo | Collection freq |
  |---|---|---|---|---|
  | Bronze | 5 | 5 | 2,000 | every 24h |
  | Silver | 10 | 10 | 10,000 | every 12h |
  | Gold | 15 | 15 | 40,000 | every 1h |
  | Diamond | 25 | 20 | 100,000 | every 10m |

- `License` entity: `id, licenseKey (unique), tier, user (OneToOne or ManyToOne), active,
  issuedAt, expiresAt`. A user uses the software via their license key (req 3).
- `LicenseTierPrice` entity (table `license_tier_prices`): `tier (unique), price, currency,
  updatedAt` — prices live in the DB and are **never** returned on any user-facing endpoint (req 14).
- Admin-only CRUD for prices and for issuing/assigning license keys.

### Prompt
```
Add a licensing model to AuraService. Each user uses the software via a license key, and each key
is one of four tiers with fixed per-tier limits. Prices live in the DB and must never be exposed
to regular users.

1. Create enum LicenseTier { BRONZE, SILVER, GOLD, DIAMOND } with fields for the per-tier limits:
   maxKeywords (5/10/15/25), maxEntities (5/10/15/20), maxMentionsPerMonth
   (2000/10000/40000/100000), and collectionFrequency (Duration: 24h/12h/1h/10m). These four enum
   constants are the single source of truth for all limit checks.
2. Create License entity (table `licenses`): id, licenseKey (unique, generated UUID-based),
   tier (enum), user (ManyToOne User, column user_id), active (boolean), issuedAt, expiresAt
   (nullable). Add LicenseRepository with findByUser and findByLicenseKey.
3. Create LicenseTierPrice entity (table `license_tier_prices`): tier (unique enum), price
   (BigDecimal), currency, updatedAt. Seed the four tiers at startup if missing (price 0 default).
4. Add a LicenseService with: resolveCurrentLicense() (license of the authenticated user),
   and helper accessors for the current user's tier + limits.
5. Admin-only endpoints under /api/admin/licenses (ROLE_ADMIN):
   - POST issue/assign a license (user + tier) -> returns the license key
   - GET list licenses, PATCH change a user's tier or active flag
   - GET/PUT /api/admin/license-prices to read & update the price catalog
6. CRITICAL: no user-facing endpoint may ever return price data. Prices are admin-only. Update the
   README DDL section with the new tables.
7. Tests for tier limit values, license assignment, and that price endpoints reject non-admins.
   Mock interfaces, not concrete classes.
```

---

## F4 — Enforce keyword & entity count limits

**Covers requirements:** 4, 5, 6

**Depends on:** F3

**Design notes**
- On entity create: reject if the user already owns `tier.maxEntities` entities.
- On keyword add/update (`PUT /api/entities/{type}/{id}/keywords`): reject if total keywords across
  **all** the user's entities would exceed `tier.maxKeywords`.
- Return a clear, structured 4xx (e.g. 409 with a `limitType`, `limit`, `current` body) so the UI
  can prompt an upgrade.

### Prompt
```
Enforce the per-license keyword and entity caps from LicenseTier in AuraService.

1. Entity cap: in EntityService create path, count entities owned by the current user. If it's
   already >= tier.maxEntities, reject with HTTP 409 and a structured body
   { limitType: "ENTITIES", limit, current }. Do not create.
2. Keyword cap: keywords are counted ACROSS ALL of the user's entities (embedded EntityKeyword
   collection). On the update-keywords endpoint (and create, if keywords are passed), compute the
   resulting total across all owned entities; if it would exceed tier.maxKeywords, reject with 409
   { limitType: "KEYWORDS", limit, current }. Allow the edit only if it stays within the cap.
3. Add a LimitException + @ExceptionHandler (or ResponseEntity) producing the structured 409 body
   consistently. Keep messages free of any price/cost info.
4. Add a read-only endpoint GET /api/license/usage returning the user's current counts vs limits
   (entities used/max, keywords used/max) WITHOUT prices, so the UI can show usage meters.
5. Tests: at-cap create is rejected; keyword total across multiple entities is enforced; an
   admin/Diamond user gets the higher caps. Mock interfaces, not concrete classes.
```

---

## F5 — Enforce monthly mention quota & collection frequency

**Covers requirements:** 7, 8

**Depends on:** F3

**Design notes**
- **Mention quota:** track mentions ingested per user per calendar month across all platforms.
  Add a `mention_usage` counter table (user_id + yyyymm + count) incremented on ingestion; when the
  tier's `maxMentionsPerMonth` is hit, stop ingesting (and/or flag over-quota) for that user until
  month rollover.
- **Collection frequency:** mention collection cadence is driven by the tier
  (10m/1h/12h/24h). The explore pass found **no existing scheduler** for ingestion, so this prompt
  introduces a scheduled collector keyed off each user's tier frequency. Confirm where ingestion
  currently happens before wiring (it may be an upstream/manual process).

### Prompt
```
Enforce two license-driven limits on mention collection in AuraService: a monthly mention quota
and a per-tier collection frequency.

First, investigate how mentions currently get into the `mentions` table (controller? proxy?
upstream batch?) and tell me what you find before changing ingestion — there is no scheduler today.

Then:
1. Monthly quota: add a MentionUsage entity/table (user_id, periodYyyyMm, count, unique on
   user+period). On each mention ingested for a user's entity, increment the counter. When the
   count reaches tier.maxMentionsPerMonth, stop accepting new mentions for that user for the rest
   of the calendar month (log + skip). Counts reset naturally at month rollover by keying on period.
2. Expose GET /api/license/usage (extend the F4 usage endpoint) to also report mentions used/max
   for the current month. No prices.
3. Collection frequency: drive ingestion cadence from tier.collectionFrequency
   (Diamond 10m, Gold 1h, Silver 12h, Bronze 24h). Implement a @Scheduled collector that, per user,
   only triggers collection when their tier's interval has elapsed since their last run (store
   lastCollectedAt per user/license). Enable @EnableScheduling if not already on.
4. Tests: quota blocks ingestion at the cap and resets across periods; a Bronze user is not
   collected more often than every 24h; Diamond every 10m. Mock interfaces, not concrete classes.
```

---

## F6 — Tier-gated premium features

**Covers requirements:** 10, 11, 12, 15, 17

**Depends on:** F3

**Design notes**
- Feature → minimum tier matrix:

  | Feature | Allowed tiers | Existing module |
  |---|---|---|
  | Checkpoints | Silver, Gold, Diamond | `/api/checkpoints` (exists) |
  | Crisis Management | Gold, Diamond | `/api/crisis` (exists) |
  | Audience & Content | Diamond | **does not exist yet** — gate the endpoint when built |
  | Intelligence Report | Diamond | part of `/api/marketing` / reports |
  | Aggregated Intel | Diamond | `/api/marketing/aggregate` (exists) |

- Prefer a declarative gate: a `@RequiresTier(LicenseTier.GOLD)` method annotation + an interceptor/
  aspect that checks the current user's tier (admins bypass). Keep it centralized, not scattered.
- This feature enforces **access**; F8 handles *showing the feature but blurring the payload*.

### Prompt
```
Gate premium features in AuraService by license tier. Tier ordering is BRONZE < SILVER < GOLD <
DIAMOND. A feature is allowed if the user's tier >= the feature's minimum tier (ROLE_ADMIN bypasses).

Feature → minimum tier:
- Checkpoints (/api/checkpoints/**): SILVER
- Crisis Management (/api/crisis/**): GOLD
- Aggregated Intel (/api/marketing/aggregate/**): DIAMOND
- Intelligence Report (the marketing report / intelligence endpoints): DIAMOND
- Audience & Content: DIAMOND (note: this module may not exist yet — if not, create a stub
  controller /api/audience-content gated at DIAMOND and tell me, don't invent its internals)

Implement:
1. A declarative gate: annotation @RequiresTier(LicenseTier) + a HandlerInterceptor or Spring AOP
   aspect that resolves the current user's license tier and rejects with 403 (structured body
   { feature, requiredTier }) when below the minimum. Admins bypass. Add a tier-ordering comparison
   helper on LicenseTier.
2. Apply @RequiresTier to the controllers/endpoints above.
3. Do NOT hide the endpoints from routing — they must remain callable so the UI can present them;
   the gate returns the structured 403 (F8 will turn this into blurred previews). For now a clean
   403 is fine.
4. Tests per feature: Silver can use Checkpoints but not Crisis; Gold can use Crisis but not
   Aggregated Intel; only Diamond reaches Diamond features; admin reaches all. Mock interfaces.
```

---

## F7 — Diamond override offer key

**Covers requirement:** 16

**Depends on:** F3, F6

**Design notes**
- An **override offer key** grants full Diamond-level *feature access* on top of any purchased tier,
  without changing the underlying tier's limits unless you decide otherwise. Decide explicitly
  whether the override also lifts numeric limits (keywords/entities/mentions) or only unlocks
  Diamond *features* — the prompt asks Claude to confirm. Default recommendation: override unlocks
  **feature gating** (F6) to Diamond and lifts limits to Diamond too, for a clean "trial of Diamond".
- Model as an `OfferKey` (code, grantsTier=DIAMOND, active, expiresAt, optional maxRedemptions) that
  a user redeems; redemption sets an effective tier on their license.

### Prompt
```
Add an "override offer key" mechanism to AuraService that grants Diamond-level access on top of any
purchased license tier.

First confirm the intended semantics with me by stating your assumption clearly: I want the
override to unlock Diamond FEATURE gating (F6) AND raise the numeric limits (keywords/entities/
mentions/frequency) to Diamond while active. Implement that unless I say otherwise.

1. Create OfferKey entity (table `offer_keys`): code (unique), grantsTier (default DIAMOND),
   active, expiresAt (nullable), maxRedemptions (nullable), redemptionCount. Admin-only CRUD under
   /api/admin/offer-keys.
2. Add an effectiveTier concept: License gets an `overrideTier` + `overrideExpiresAt` (nullable).
   LicenseService.effectiveTier() returns overrideTier if present and not expired, else the base
   tier. ALL limit checks (F4/F5) and feature gates (F6) must use effectiveTier(), not the raw tier.
3. Endpoint POST /api/license/redeem-offer { code } for the authenticated user: validate the offer
   key (active, not expired, redemptions left), set overrideTier=DIAMOND (+ expiry from the key),
   increment redemptionCount. Reject invalid/expired/exhausted keys with a clear 4xx.
4. Make sure prices are never exposed here either.
5. Tests: a Bronze user who redeems a valid key gets Diamond features and Diamond limits; expired/
   exhausted/invalid keys are rejected; expiry causes fallback to the base tier. Mock interfaces.
```

---

## F8 — Universal feature visibility with blurred previews

**Covers requirement:** 18

**Depends on:** F6 (and ideally all prior)

**Design notes**
- The UX goal: **every feature is visible in the UI**, but the API tells the client whether the
  user may see real data. If not entitled, the API returns a **blurred/obfuscated** version of the
  payload (teaser) instead of a hard 403 — to entice upgrades.
- Convert the F6 hard-403 gates into an **entitlement-aware envelope**: a standard response wrapper

  ```json
  { "entitled": true|false, "requiredTier": "DIAMOND", "data": { ... }, "preview": { ... } }
  ```

  When `entitled=false`, omit/empty `data` and return a `preview` that is masked (e.g. counts
  jittered/bucketed, strings starred out, lists truncated) — never the real values, so it can't be
  scraped. Provide a reusable masking utility.
- Add a lightweight `GET /api/license/features` capabilities endpoint so the UI can render the full
  feature list with locked/unlocked badges in one call.

### Prompt
```
Change AuraService premium gating from hard 403s to "visible-but-blurred" responses so every
feature shows in the UI and the API entices upgrades.

1. Define a generic response envelope EntitledResponse<T> { boolean entitled, LicenseTier
   requiredTier, T data, Object preview }. entitled is based on the current user's effectiveTier vs
   the feature's required tier (reuse F6/F7 logic; admins always entitled).
2. For each tier-gated feature (Checkpoints, Crisis, Audience & Content, Intelligence Report,
   Aggregated Intel), instead of returning 403 when not entitled, return 200 with
   entitled=false, data=null, and a `preview` that is a MASKED version of the real payload:
   - numbers bucketed/jittered (never exact), strings replaced with a starred placeholder,
     lists truncated to a teaser length. Build a reusable PreviewMaskingService and unit-test that
     no real underlying value leaks into preview.
3. Add GET /api/license/features returning the full catalog of features with { key, name,
   requiredTier, entitled } for the current user, so the UI renders all features with lock badges.
   No prices anywhere.
4. Keep numeric-limit enforcement (F4/F5) as real 409s — blurring applies to FEATURE data, not to
   "you hit your keyword cap".
5. Tests: an unentitled user gets entitled=false + masked preview (assert exact values absent); an
   entitled user gets entitled=true + real data; capabilities endpoint reflects tier correctly.
   Mock interfaces, not concrete classes.
```

---

## Requirement → Feature coverage map

| # | Requirement (short) | Feature |
|---|---|---|
| 1 | User mapped only to entities they added | F1 |
| 2 | Admin sees all + user selector | F2 |
| 3 | License key per user | F3 |
| 4 | License limits checked in backend DB | F3 + F4/F5 |
| 5 | Keyword cap 5/10/15/25 | F4 |
| 6 | Entity cap 5/10/15/20 | F4 |
| 7 | Mentions/mo 2k/10k/40k/100k | F5 |
| 8 | Collection freq 10m/1h/12h/24h | F5 |
| 9 | Key is one of 4 tiers | F3 |
| 10 | Audience & Content = Diamond | F6 |
| 11 | Crisis Mgmt = Gold+Diamond | F6 |
| 12 | Checkpoints = Silver+ | F6 |
| 13 | Tier price set in DB | F3 |
| 14 | Prices hidden from users | F3 (enforced across all) |
| 15 | Intelligence Report = Diamond | F6 |
| 16 | Diamond override offer key | F7 |
| 17 | Aggregated Intel = Diamond | F6 |
| 18 | All features visible, blurred if no access | F8 |

## Suggested execution sequence

1. **F1** entity ownership (foundational, touches many controllers)
2. **F2** admin cross-user access
3. **F3** license model + price catalog (backbone)
4. **F4** keyword/entity caps → **F5** mention quota/frequency (parallel-ish after F3)
5. **F6** tier-gated features
6. **F7** override offer key
7. **F8** blurred-preview envelope (last — reshapes F6 responses)

Each prompt is self-contained but assumes the prior features are merged. Run one feature per
branch/PR, review, then proceed.
