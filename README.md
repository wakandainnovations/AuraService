# AuraService - Online Reputation Management System

A complete Java Spring Boot backend application for managing online reputation for celebrities and movies.

## Technology Stack

- **Java Version:** 17
- **Framework:** Spring Boot 3.2.0
- **Database:** H2 (in-memory)
- **Authentication:** Spring Security 6 with JWT
- **PDF Generation:** OpenPDF 1.3.30
- **Build Tool:** Maven

## Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6 or higher

### Running the Application

```bash
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

### Building and Running the Executable JAR

To build an executable JAR file, run the following command:

```bash
mvn clean package
```

This will create a file named `aura-service-1.0.0.jar` in the `target` directory.

To run the application from the JAR file, use the following command:

```bash
java -jar target/aura-service-1.0.0.jar
```

The application will start on `http://localhost:8080`.

### Default Credentials

- **Username:** `user`
- **Password:** `password`
- **Timezone:** `America/New_York`

A seeded administrator (`ROLE_ADMIN`) account is also created:

- **Username:** `admin`
- **Password:** `admin`

Admins can read **every** user's entities and scope any list to a specific user via the `ownerId`
query parameter (see **Entity ownership** below), and have access to the admin-only endpoints under
`/api/admin/**` and `/api/audit-logs/**`.

### PostGres

- db.url=jdbc:postgresql://localhost:5432/aura
- db.user=mukundv
- db.password=

## Database Initialization (PostgreSQL)

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    alert_webhook_url VARCHAR(255),
    timezone VARCHAR(255) NOT NULL DEFAULT 'UTC'
);

CREATE TABLE managed_entities (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    -- The user who owns this entity. Every API write stamps it, and a user only ever sees and acts
    -- on entities they own. Left nullable so that under ddl-auto=update (no Flyway) the column can be
    -- added to an already-populated table; a startup backfill (EntityOwnerBackfill) then assigns any
    -- legacy null owner_id to the seeded admin user. On a fresh database it is always populated.
    owner_id BIGINT,
    director VARCHAR(255),
    release_date DATE,
    language VARCHAR(255),
    industry VARCHAR(255),
    genre VARCHAR(255),
    synopsis TEXT,
    CONSTRAINT fk_managed_entities_owner FOREIGN KEY (owner_id) REFERENCES users(id)
);

CREATE TABLE entity_actors (
    entity_id BIGINT NOT NULL,
    actor VARCHAR(255),
    CONSTRAINT fk_entity_actors_managed_entities FOREIGN KEY (entity_id) REFERENCES managed_entities(id)
);

CREATE TABLE entity_keywords (
    entity_id BIGINT NOT NULL,
    keyword VARCHAR(255),
    category VARCHAR(255),
    language TEXT,
    state TEXT,
    industry TEXT,
    genre TEXT,
    CONSTRAINT fk_entity_keywords_managed_entities FOREIGN KEY (entity_id) REFERENCES managed_entities(id)
);

CREATE TABLE entity_competitors (
    entity_id BIGINT NOT NULL,
    competitor_id BIGINT NOT NULL,
    CONSTRAINT fk_entity_competitors_entity FOREIGN KEY (entity_id) REFERENCES managed_entities(id),
    CONSTRAINT fk_entity_competitors_competitor FOREIGN KEY (competitor_id) REFERENCES managed_entities(id)
);

CREATE TABLE mentions (
    id BIGSERIAL PRIMARY KEY,
    managed_entity_id BIGINT NOT NULL,
    platform VARCHAR(255) NOT NULL,
    post_id VARCHAR(255) UNIQUE NOT NULL,
    content TEXT,
    author VARCHAR(255),
    post_date TIMESTAMP NOT NULL,
    sentiment VARCHAR(255) NOT NULL,
    CONSTRAINT fk_mentions_managed_entities FOREIGN KEY (managed_entity_id) REFERENCES managed_entities(id)
);

CREATE TABLE user_entity_views (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    entity_id BIGINT NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_user_entity_views_user_entity UNIQUE (user_id, entity_id)
);

-- A license key issued to a user. Each user operates under one active license; the tier fixes the
-- per-tier limits (max keywords/entities/mentions-per-month and collection frequency), which live
-- in code as the LicenseTier enum (the single source of truth). Prices are NOT stored here.
CREATE TABLE licenses (
    id BIGSERIAL PRIMARY KEY,
    license_key VARCHAR(255) UNIQUE NOT NULL,
    tier VARCHAR(255) NOT NULL,
    user_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    CONSTRAINT fk_licenses_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- The price catalog for each license tier. SENSITIVE, ADMIN-ONLY data: it is exposed exclusively
-- through GET/PUT /api/admin/license-prices (ROLE_ADMIN) and must never be returned by any
-- user-facing endpoint. The tier is the natural primary key, so there is one price row per tier.
-- Seeded at startup with price 0 for every tier if missing.
CREATE TABLE license_tier_prices (
    tier VARCHAR(255) PRIMARY KEY,
    price NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

## API Documentation

All endpoints except `/api/auth/*` require JWT authentication. Include the JWT token in the `Authorization` header as `Bearer {token}`.

**Entity ownership:** Managed entities are owned by the user who creates them. A user only sees and acts on their own entities — listing is owner-scoped, and any read/update/delete (or any other endpoint that operates on an entity by id, including the dashboard, checkpoint, crisis, mention-action, analytics, and marketing endpoints) returns `404 Not Found` when the referenced entity does not exist **or** belongs to another user. The two cases are deliberately indistinguishable so the API never reveals the existence of another user's entities.

**Admin access (`ROLE_ADMIN`):** Administrators bypass the ownership restriction — they can read **every** user's entities through all of the same endpoints. List and entity-keyed endpoints additionally accept an optional `ownerId` query parameter to scope the view to a single user:

- **Admin, no `ownerId`** — sees all entities across every user (listing returns everyone's entities).
- **Admin, `ownerId={userId}`** — scopes the result to that user's entities; an entity-keyed request (e.g. a dashboard or mention-action call) whose target does **not** belong to `ownerId` returns `404 Not Found`.
- **Non-admin passing `ownerId`** — rejected with `403 Forbidden`. Regular users may never scope by `ownerId`; omitting it preserves the normal owner-scoped behavior described above.

The list of users to populate an admin user-selector is available from `GET /api/admin/users` (see **Admin APIs**).

---

## Authentication APIs

### 1. Register User

**Endpoint:** `POST /api/auth/register`

**Description:** Register a new user account

**Request Body:**
```json
{
  "username": "newuser",
  "password": "password123"
}
```

**Response:**
```json
"User registered successfully"
```

**Status Code:** `200 OK`

---

### 2. Login

**Endpoint:** `POST /api/auth/login`

**Description:** Login and receive JWT token

**Request Body:**
```json
{
  "username": "user",
  "password": "password"
}
```

**Response:**
```json
{
  "jwtToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIiwiaWF0IjoxNjk5..."
}
```

**Status Code:** `200 OK`

---

## Entity Management APIs

### 3. Create Managed Entity

**Endpoint:** `POST /api/entities/{entityType}`

**Description:** Create a new managed entity (celebrity or movie). The created entity is **owned by the authenticated user** (resolved from the JWT) — only that user can subsequently see or act on it. Ownership is assigned by the server; there is no `owner` field in the request body.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)

**Request Body:**
```json
{
  "name": "The Matrix Resurrections",
  "type": "MOVIE",
  "director": "Lana Wachowski",
  "actors": ["Keanu Reeves", "Carrie-Anne Moss", "Yahya Abdul-Mateen II"],
  "keywords": ["keanureeves", "matrix", "sequel"],
  "language": "English",
  "industry": "Hollywood",
  "genre": ["Science Fiction", "Action"],
  "synopsis": "A hacker uncovers a conspiracy that pulls him back into a simulated reality he thought he'd left behind."
}
```

> `language`, `genre`, and `synopsis` are optional and only applied when `entityType` is `movie`. `industry` is optional and applied when `entityType` is `movie` or `celebrity`. `synopsis` is capped at 5000 characters and is the free-text plot summary consumed by narrative-metric scoring (see [Get Conflict Balance](#28-get-protagonist-antagonist-conflict-balance) and [Get Narrative Novelty](#29-get-high-concept-narrative-novelty)).
>
> Keywords carry only their text in the request; each stored `entity_keywords` row is stamped from the entity's own classification — `category` from the type (`media.movie` / `media.celebrity`), plus the entity's `language`, `industry`, and `genre`. A movie's multiple genres are stored on the single keyword row as a comma-separated `genre` value; readers of the column (marketing filters and genre aggregation) split it back into individual genres.

**Response:**
```json
{
  "id": 5,
  "name": "The Matrix Resurrections",
  "type": "MOVIE",
  "director": "Lana Wachowski",
  "actors": ["Keanu Reeves", "Carrie-Anne Moss", "Yahya Abdul-Mateen II"],
  "keywords": ["keanureeves", "matrix", "sequel"],
  "industry": "Hollywood",
  "genre": ["Science Fiction", "Action"],
  "synopsis": "A hacker uncovers a conspiracy that pulls him back into a simulated reality he thought he'd left behind.",
  "competitors": []
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `409 Conflict` — the create would breach a per-tier cap and nothing is created: the user is already at `maxEntities` (`limitType: "ENTITIES"`), or the supplied keywords would push the account-wide keyword total past `maxKeywords` (`limitType: "KEYWORDS"`). See [Licensing & Usage APIs](#licensing--usage-apis) for the body shape.

---

### 4. Update Managed Entity

**Endpoint:** `PUT /api/entities/{entityType}/{id}`

**Description:** Update an existing managed entity's editable details. This is a full replace of the editable fields, so send the complete set of values the entity should have after the edit (any field omitted is cleared). Intended for the UI's "edit entity" flow.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)
- `id` - Entity ID (e.g., 5)

**Request Body:**
```json
{
  "name": "Dune: Part Two",
  "director": "Denis Villeneuve",
  "actors": ["Timothee Chalamet", "Zendaya", "Rebecca Ferguson"],
  "keywords": [{"keyword": "dune"}],
  "releaseDate": "2024-03-01",
  "language": "English",
  "industry": "Hollywood",
  "genre": ["Science Fiction", "Adventure"],
  "synopsis": "Paul Atreides unites with the Fremen to seek revenge against the conspirators who destroyed his family."
}
```

> `name` is required. `releaseDate`, `language`, `genre`, and `synopsis` are only applied when `entityType` is `movie`; `industry` is applied when `entityType` is `movie` or `celebrity`. `genre` accepts multiple comma-separated values as a JSON list. This is a full replace — an omitted `synopsis` clears it. Note: like the box-office prediction endpoint, [Get Conflict Balance](#28-get-protagonist-antagonist-conflict-balance) and [Get Narrative Novelty](#29-get-high-concept-narrative-novelty) each cache their result per movie ID for the life of the server process, so editing `synopsis` here does **not** invalidate an already-cached score. Keywords carry only their text — each stored `entity_keywords` row is stamped from the entity's `category` (from the type), `language`, `industry`, and one row per `genre` (see [Create Managed Entity](#3-create-managed-entity)). Competitors are managed via the separate competitors endpoint and are not affected by this call.

**Response:** The updated entity, in the same shape as [Get Entity by ID](#6-get-entity-by-id).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` - No such entity, or the entity is owned by another user (indistinguishable by design).
- `400 Bad Request` - The entity is owned by the caller but is not of the given `entityType`, or the body fails validation (e.g. missing `name`).

---

### 5. Get All Entities

**Endpoint:** `GET /api/entities/{entityType}`

**Description:** Retrieve a list of the managed entities of a specific type **owned by the authenticated user**. The list is owner-scoped — entities created by other users are never returned.

**Admin behavior:** An admin (`ROLE_ADMIN`) sees **all** entities of the type by default. Supplying `ownerId` scopes the list to that user's entities. A non-admin who supplies `ownerId` is rejected with `403 Forbidden`.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)

**Query Parameters:**
- `ownerId` — (admin only) scope the list to the entities owned by this user id. Omit to list all entities (admin) or your own entities (regular user). Optional.

**Example Requests:**
```
GET /api/entities/movie                 # your own movies (regular user) / all movies (admin)
GET /api/entities/movie?ownerId=3        # admin only: movies owned by user 3
```

**Response:**
```json
[
  {
    "id": 1,
    "name": "The Quantum Paradox",
    "type": "MOVIE"
  },
  {
    "id": 3,
    "name": "Inception 2",
    "type": "MOVIE"
  }
]
```

**Status Code:** `200 OK`

---

### 6. Get Entity by ID

**Endpoint:** `GET /api/entities/{entityType}/{id}`

**Description:** Retrieve detailed information about a specific entity owned by the authenticated user.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)
- `id` - Entity ID (e.g., 1)

**Response:**
```json
{
  "id": 1,
  "name": "The Quantum Paradox",
  "type": "MOVIE",
  "ownerId": 2,
  "director": "Christopher Nolan",
  "actors": ["Leonardo DiCaprio", "Emma Stone", "Tom Hardy"],
  "keywords": ["sci-fi", "thriller", "mind-bending"],
  "competitors": [
    {
      "id": 3,
      "name": "Inception 2",
      "type": "MOVIE"
    },
    {
      "id": 4,
      "name": "Interstellar Reloaded",
      "type": "MOVIE"
    }
  ],
  "releaseDate": "2026-07-01",
  "language": "English",
  "industry": "Hollywood",
  "genre": ["Science Fiction", "Thriller"],
  "synopsis": "A physicist's discovery threatens to unravel reality itself.",
  "budget": 200000000.0,
  "productionCompany": "Legendary Pictures",
  "runtime": 148,
  "releaseDay": "Wednesday",
  "gdpUsdBillions": 3346.11,
  "inflationRatePct": 6.7,
  "imageUrl": "/entities/movie/1/image",
  "imagePath": "the-quantum-paradox.jpg"
}
```

This is the full set of columns stored on the entity — every field in `EntityDetailResponse` is returned regardless of `entityType` (fields not applicable to `celebrity` entities, e.g. `releaseDate`/`budget`/`runtime`, are simply `null`).

**Response fields (beyond the obvious):**
- `ownerId` — the id of the user who owns the entity (see **Entity ownership** above); never another user's, since access is owner-scoped.
- `imageUrl` — the path the UI fetches the poster image from (`GET /api/entities/{entityType}/{id}/image`), or `null` if no image has been matched for this entity yet.
- `imagePath` — the raw stored filename the image is resolved from on disk; `null` under the same condition as `imageUrl`. Most UIs should use `imageUrl` rather than constructing a path from this field.
- `releaseDay`, `gdpUsdBillions`, `inflationRatePct` — derived server-side from `releaseDate` (movies only); never client-supplied and `null` when there is no `releaseDate`.

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` - No such entity, or the entity is owned by another user (indistinguishable by design).
- `400 Bad Request` - The entity is owned by the caller but is not of the given `entityType`.

---

### 7. Update Competitors

**Endpoint:** `PUT /api/entities/{entityType}/{id}/competitors`

**Description:** Update the list of competitors for an entity

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)
- `id` - Entity ID (e.g., 1)

**Request Body:**
```json
{
  "competitorIds": [3, 4, 5]
}
```

> Only entities **owned by the caller** can be added as competitors. Any `competitorIds` that do not exist or belong to another user are silently ignored (they are not added and do not cause an error).

**Response:**
```json
{
  "id": 1,
  "name": "The Quantum Paradox",
  "type": "MOVIE",
  "director": "Christopher Nolan",
  "actors": ["Leonardo DiCaprio", "Emma Stone", "Tom Hardy"],
  "keywords": ["sci-fi", "thriller", "mind-bending"],
  "competitors": [
    {
      "id": 3,
      "name": "Inception 2",
      "type": "MOVIE"
    },
    {
      "id": 4,
      "name": "Interstellar Reloaded",
      "type": "MOVIE"
    },
    {
      "id": 5,
      "name": "The Matrix Resurrections",
      "type": "MOVIE"
    }
  ]
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` - No such entity, or the entity is owned by another user (indistinguishable by design).
- `400 Bad Request` - The entity is owned by the caller but is not of the given `entityType`.

---

### 8. Update Keywords

**Endpoint:** `PUT /api/entities/{entityType}/{id}/keywords`

**Description:** Update the list of keywords for an entity

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)
- `id` - Entity ID (e.g., 1)

**Request Body:**
```json
{
  "keywords": ["new-keyword-1", "new-keyword-2"]
}
```

**Response:**
```json
{
  "id": 1,
  "name": "The Quantum Paradox",
  "type": "MOVIE",
  "director": "Christopher Nolan",
  "actors": ["Leonardo DiCaprio", "Emma Stone", "Tom Hardy"],
  "keywords": ["new-keyword-1", "new-keyword-2"],
  "competitors": [
    {
      "id": 3,
      "name": "Inception 2",
      "type": "MOVIE"
    },
    {
      "id": 4,
      "name": "Interstellar Reloaded",
      "type": "MOVIE"
    }
  ]
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` - No such entity, or the entity is owned by another user (indistinguishable by design).
- `400 Bad Request` - The entity is owned by the caller but is not of the given `entityType`.
- `409 Conflict` - The new keyword set would push the user's account-wide keyword total past `maxKeywords`; the edit is rejected (`limitType: "KEYWORDS"`). Keywords are counted across **all** the user's entities, so this can trip even when the count for this one entity is unchanged. See [Licensing & Usage APIs](#licensing--usage-apis).

---

### 9. Delete Entity

**Endpoint:** `DELETE /api/entities/{entityType}/{id}`

**Description:** Delete an entity. Any checkpoints belonging to the entity are removed, and the entity is detached from any other entity's competitor list before deletion.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)
- `id` - Entity ID (e.g., 1)

**Example:**
```
DELETE /api/entities/movie/1
```

**Response:** Empty body.

**Status Code:** `204 No Content`

**Error Responses:**
- `404 Not Found` - No such entity, or the entity is owned by another user (the two are indistinguishable by design, so existence is never leaked).
- `400 Bad Request` - The entity is owned by the caller but is not of the given `entityType`.

---

### 8a. Generate Entity Marketing Report

**Endpoint:** `GET /api/entities/{entityType}/{id}/marketing-report`

**Description:** Generate a single, complete, **prospect-facing** marketing intelligence report for a managed entity. It is designed to be shown at a high level to a production house's potential customers, so the most flattering, headline numbers are surfaced first and a deterministic `highlights` narrative summarizes them.

> **Tier-gated (DIAMOND).** The Intelligence Report (`/marketing-report` and `/marketing-report/pdf`) requires the **DIAMOND** tier. It is not blocked: a lower-tier caller still gets `200 OK` with an `EntitledResponse` whose `entitled=false` and a **masked `preview`** of the report (the PDF route returns the same masked JSON envelope rather than a PDF); admins are always entitled. See [Premium Feature Tier Gating](#premium-feature-tier-gating).

The report aggregates this service's own analytics with the upstream **AuraMath** entity report:

- **Headline metrics** — total mentions, overall sentiment, positivity ratio, positive/negative/neutral split, net sentiment score, and the number of platforms covered (from the same data behind `GET /api/dashboard/{entityId}/stats` and `/stats/avg`).
- **Competitive positioning** — the entity plus every tracked competitor (the competitor snapshot), ranked by net sentiment, with the entity's `rank`, the current `leaderName`, and a `leadsCategory` flag.
- **Sentiment trend** — the sentiment-over-time series with checkpoint markers for the selected `period`.
- **Platform reach** — per-platform mention counts broken down by sentiment.
- **Defining moments** — before/after checkpoint impact (same shape as `GET /api/dashboard/{entityId}/checkpoint-impact`).
- **AuraMath intelligence** — the upstream `GET /api/marketing/entity-report/{entityId}` payload, embedded verbatim. The internal numeric entity `id` is reused as the AuraMath `entityId` (an opaque `managed_entities` id).
- **Highlights** — deterministic, human-readable bullets derived from the metrics above, ready to render directly in a deck or one-pager.

**Graceful degradation:** every section except the entity profile and headline metrics is optional. If a downstream source (notably AuraMath) is unavailable, that section is omitted (`null`) rather than failing the whole report — `auraMathStatus` reports `"ok"` or `"unavailable"`. Only a genuinely missing entity fails the request.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)
- `id` - Entity ID (e.g., 1)

**Query Parameters:**
- `period` (optional, default: `DAY30`) — Window for the sentiment trend / momentum sections. One of `DAY`, `DAY15`, `DAY30`, `DAY90`, `WEEK`, `MONTH`, `MONTH6`.
- `windowDays` (optional, default: `7`) — Days before/after each checkpoint for the defining-moments impact. Must be 1–30.

**Example Request:**
```
GET /api/entities/movie/1/marketing-report?period=DAY30&windowDays=7
```

**Response:** Wrapped in an [`EntitledResponse`](#premium-feature-tier-gating) envelope. For an **entitled** caller the report below is the `data` field (`entitled: true`, `requiredTier: "DIAMOND"`, `preview: null`). A caller below **DIAMOND** instead gets `200 OK` with `entitled: false`, `data: null`, and a masked `preview` of the same report — every number bucketed (e.g. `8000` → `"thousands"`), every string starred, and lists truncated — so no real figure leaks.

The report payload (the `data` field, sections abbreviated for readability):
```json
{
  "generatedAt": "2026-06-11T08:30:00Z",
  "period": "DAY30",
  "entity": {
    "id": 1,
    "name": "The Quantum Paradox",
    "type": "MOVIE",
    "director": "Christopher Nolan",
    "actors": ["Leonardo DiCaprio", "Emma Stone", "Tom Hardy"],
    "keywords": ["sci-fi", "thriller", "mind-bending"],
    "competitors": [
      { "id": 3, "name": "Inception 2", "type": "MOVIE" }
    ],
    "releaseDate": "2026-07-01"
  },
  "headlineMetrics": {
    "totalMentions": 8000,
    "overallSentiment": 0.62,
    "positivityRatio": 0.70,
    "positiveSentiment": 0.70,
    "negativeSentiment": 0.14,
    "neutralSentiment": 0.16,
    "netSentimentScore": 5.0,
    "platformsCovered": 4
  },
  "competitivePositioning": {
    "snapshot": [
      { "entityName": "The Quantum Paradox", "totalMentions": 8000, "overallSentiment": 0.62, "positiveRatio": 0.70, "netSentimentScore": 5.0 },
      { "entityName": "Inception 2", "totalMentions": 5000, "overallSentiment": 0.40, "positiveRatio": 0.50, "netSentimentScore": 2.0 }
    ],
    "totalTracked": 2,
    "rank": 1,
    "leadsCategory": true,
    "leaderName": "The Quantum Paradox"
  },
  "sentimentTrend": {
    "entities": [
      {
        "name": "The Quantum Paradox",
        "sentiments": [
          { "date": "2026-05-13", "positive": 220, "negative": 40, "neutral": 30 }
        ],
        "checkpoints": [
          { "date": "2026-05-20", "description": "Trailer Launch" }
        ]
      }
    ]
  },
  "platformReach": {
    "YOUTUBE": { "POSITIVE": 212, "NEGATIVE": 53, "NEUTRAL": 13 },
    "INSTAGRAM": { "POSITIVE": 37, "NEGATIVE": 1, "NEUTRAL": 3 }
  },
  "definingMoments": {
    "entityId": 1,
    "entityName": "The Quantum Paradox",
    "windowDays": 7,
    "impacts": [
      {
        "checkpointId": 10,
        "checkpointDate": "2026-05-20",
        "description": "Trailer Launch",
        "positiveRatioChange": 0.12,
        "netSentimentChange": 1.95,
        "impactDirection": "IMPROVED"
      }
    ]
  },
  "auraMathIntelligence": {
    "score": 91,
    "verdict": "blockbuster"
  },
  "auraMathStatus": "ok",
  "highlights": [
    "8.0K mentions analysed across 4 platforms of audience conversation",
    "70% of all mentions are positive",
    "5.0 positive mentions for every negative one",
    "Leads its category — #1 of 2 tracked titles on net sentiment",
    "Strongest reach on YOUTUBE",
    "Tracking sentiment around the 2026-07-01 release"
  ]
}
```

**Response fields:**
- `auraMathStatus` — `"ok"` when the upstream AuraMath report was embedded in `auraMathIntelligence`, otherwise `"unavailable"` (and `auraMathIntelligence` is omitted).
- `competitivePositioning.snapshot` — the entity plus its tracked competitors, each with its own reach/sentiment metrics; `rank` is the entity's 1-based standing by net sentiment, and `leadsCategory` is `true` when it tops that ranking.
- `highlights` — deterministic strings derived from the metrics; the set returned depends on which sections were available and which thresholds were crossed.
- Any optional section that could not be computed (e.g. `sentimentTrend`, `competitivePositioning`, `platformReach`, `definingMoments`, `auraMathIntelligence`) is omitted from the payload rather than returned as `null`.

The embedded AuraMath payload is the same one exposed by the proxy wrapper `GET /v1/marketing/entity-report/{entityId}` — see [AuraMath Marketing Proxy](#auramath-marketing-proxy-v1marketing).

**Status Codes:**
- `200 OK` — Report generated (possibly with some optional sections omitted).
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).
- `400 Bad Request` — The entity is owned by the caller but is not of the given `entityType`, or `windowDays` is outside 1–30.

---

### 8b. Generate Entity Marketing Report (PDF)

**Endpoint:** `GET /api/entities/{entityType}/{id}/marketing-report/pdf`

**Description:** The same complete marketing intelligence report as [8a](#8a-generate-entity-marketing-report), rendered as a polished, downloadable **PDF** suitable for sharing directly with a production house's potential customers. It runs the identical aggregation (so the same `period` / `windowDays` parameters apply and the same graceful-degradation rules hold) and lays the result out as a branded document: a header with the entity profile, the highlights, headline-metric cards, and tables for competitive positioning, platform reach, defining moments, the sentiment trend, and the embedded AuraMath intelligence. Sections absent from the underlying report are simply omitted from the PDF.

The PDF is generated server-side with [OpenPDF](https://github.com/LibrePDF/OpenPDF); no upstream PDF call is involved (contrast with the proxy passthrough `GET /v1/marketing/entity-report/{entityId}`, which forwards AuraMath's own PDF).

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)
- `id` - Entity ID (e.g., 1)

**Query Parameters:**
- `period` (optional, default: `DAY30`) — Window for the sentiment trend / momentum sections. One of `DAY`, `DAY15`, `DAY30`, `DAY90`, `WEEK`, `MONTH`, `MONTH6`.
- `windowDays` (optional, default: `7`) — Days before/after each checkpoint for the defining-moments impact. Must be 1–30.

**Example Request:**
```
GET /api/entities/movie/1/marketing-report/pdf?period=DAY30
```

**cURL example (saves the file):**
```bash
curl -X GET "http://localhost:8080/api/entities/movie/1/marketing-report/pdf" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -OJ
```

**Response (entitled caller):** Binary `application/pdf` body.

**Response Headers (entitled caller):**
- `Content-Type: application/pdf`
- `Content-Disposition: attachment; filename="marketing-report-{slug}.pdf"` — the filename is derived from the entity name, lower-cased and slugified (e.g. `The Quantum Paradox` → `marketing-report-the-quantum-paradox.pdf`; falls back to `marketing-report-entity.pdf` when the name is missing).

> **Tier behavior.** This route is tier-gated like [8a](#8a-generate-entity-marketing-report). An **entitled** caller (DIAMOND or admin) gets the PDF described above. A caller **below DIAMOND** gets `200 OK` with `Content-Type: application/json` carrying the **same masked [`EntitledResponse`](#premium-feature-tier-gating)** envelope as 8a (`entitled: false`, `data: null`, masked `preview`) — **no PDF is rendered**.

**Status Codes:**
- `200 OK` — PDF generated (entitled), or the masked JSON envelope (not entitled).
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).
- `400 Bad Request` — The entity is owned by the caller but is not of the given `entityType`, or `windowDays` is outside 1–30.

---

## Licensing & Usage APIs

Every user operates under exactly one active **license**, whose **tier** fixes the per-tier limits the
account is allowed to consume. The tiers and their caps are defined in code by the `LicenseTier` enum
(the single source of truth — no limit value is hard-coded anywhere else):

| Tier | Max entities | Max keywords |
|---------|:---:|:---:|
| BRONZE | 5 | 5 |
| SILVER | 10 | 10 |
| GOLD | 15 | 15 |
| DIAMOND | 100 | 100 |

Two of these caps are enforced on the entity write paths:

- **Entity cap** — creating an entity is rejected when the user already owns `maxEntities` entities.
- **Keyword cap** — keywords are counted **across all of the user's entities**. Creating an entity
  with keywords, or replacing an entity's keywords, is rejected when the resulting account-wide total
  would exceed `maxKeywords`.

Both rejections return **`409 Conflict`** with a structured, **price-free** body (see
[409 Conflict](#409-conflict)):

```json
{ "limitType": "ENTITIES", "limit": 15, "current": 15 }
```

- `limitType` — `"ENTITIES"` or `"KEYWORDS"`.
- `limit` — the tier's cap for that resource.
- `current` — the value that breaches the cap: for `ENTITIES` the count the user already owns; for
  `KEYWORDS` the account-wide total the operation would have produced.

> Limits are user-facing, but **prices are not** — they are admin-only (`/api/admin/license-prices`,
> `ROLE_ADMIN`) and never appear in any license, usage, or limit response.

### Premium Feature Tier Gating

Beyond the numeric caps above, whole **premium features** are gated by a minimum tier. A feature is
allowed only when the caller's tier is **at least** the feature's minimum, using the fixed ordering:

```
BRONZE  <  SILVER  <  GOLD  <  DIAMOND
```

The gate is **visible-but-blurred**, not a hard block: a gated endpoint always answers `200 OK` with a
generic envelope so the UI can render every feature — live for entitled users, or as a locked, blurred
teaser that entices an upgrade. Entitlement reuses the single rule (admin, or effective tier at least
the feature's minimum); holders of `ROLE_ADMIN` are always entitled regardless of tier.

| Feature | Endpoint(s) | Minimum tier |
|---------|-------------|:---:|
| Checkpoints | `/api/checkpoints/**` | SILVER |
| Crisis Management | `/api/crisis/**` | GOLD |
| Aggregated Intel | `/api/marketing/aggregate/**`, `/api/marketing/audience-patterns/**` | DIAMOND |
| Intelligence Report | `/api/entities/{entityType}/{id}/marketing-report[/pdf]` | DIAMOND |
| Audience & Content | `/api/audience-content` *(stub — module not yet implemented)* | DIAMOND |

The envelope is `EntitledResponse<T>`:

```json
{ "entitled": false, "requiredTier": "GOLD", "data": null,
  "preview": { "generatedPlan": "★★★★★" } }
```

- `entitled` — whether the caller may use the feature.
- `requiredTier` — the minimum tier that unlocks it (never the price of that tier).
- `data` — the real payload, present only when `entitled`; otherwise `null`.
- `preview` — a **masked** teaser of the payload, present only when **not** entitled; otherwise `null`.
  Strings become a starred placeholder, numbers collapse to coarse digit-free buckets (never the exact
  value), and lists are truncated — so no real underlying value ever leaks into the preview.

For a mutating endpoint (e.g. creating a checkpoint) an unentitled caller gets `entitled=false` with no
`preview`, and the mutation never runs.

To render all features up-front with lock badges, the UI can read the full catalog from
[`GET /api/license/features`](#license-feature-catalog), which returns
`{ key, name, requiredTier, entitled }` per feature for the current user.

> **Numeric caps still hard-fail.** Blurring applies to *feature data* only. Hitting a numeric limit
> (keywords/entities, F4/F5) is still a real `409 Conflict` — see [Licensing & Usage APIs](#licensing--usage-apis).

### License Feature Catalog

To render all features up-front with lock badges, the UI reads the whole catalog with per-user
entitlement from `GET /api/license/features` — see [L4. List Premium Features](#l4-list-premium-features)
for the full contract. It returns `{ key, name, requiredTier, entitled }` per feature and is
deliberately **price-free** (names the required tier, never its cost).

### Offer-key overrides (effective tier)

A user's **effective tier** is normally their purchased license tier — but it can be temporarily
raised by **redeeming an offer key** (see [Redeem Offer Key](#l3-redeem-offer-key)). While an override
is active, the effective tier replaces the base tier **everywhere it matters**: both the numeric caps
(F4/F5) and the premium-feature gates (F6) are evaluated against the effective tier, so a Bronze user
who redeems a Diamond offer key gets Diamond limits **and** Diamond features until the override
expires.

The override lives on the license as `overrideTier` + `overrideExpiresAt`:

- It applies only while `overrideTier` is set **and** `overrideExpiresAt` is still in the future (a
  `null` expiry never lapses).
- Once it expires, the effective tier silently falls back to the base purchased tier.

Both `GET /api/license/usage` and `GET /api/licenses/me` report the **effective** tier and its caps, so
the limits a user sees always match the ones enforcement applies. Offer keys grant *access*, never a
purchase — they carry **no price**. Offer keys are created and managed admin-only under
`/api/admin/offer-keys` (see [Admin APIs](#admin-apis)).

### L1. Get License Usage

**Endpoint:** `GET /api/license/usage`

**Description:** Read-only usage meter for the authenticated user: how many entities and keywords they
are currently using against their **effective** tier's caps (an active
[offer-key override](#offer-key-overrides-effective-tier) raises these). Designed to back the UI's
usage meters. Carries **no price** — only counts and limits.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Response:**
```json
{
  "entitiesUsed": 8,
  "entitiesMax": 15,
  "keywordsUsed": 11,
  "keywordsMax": 15
}
```

**Response fields:**
- `entitiesUsed` — number of entities the user owns.
- `entitiesMax` — the tier's `maxEntities`.
- `keywordsUsed` — total keywords summed across all of the user's entities.
- `keywordsMax` — the tier's `maxKeywords`.

**Example (verified):** the seeded `user` account (GOLD) returns
`{"entitiesUsed":0,"entitiesMax":15,"keywordsUsed":0,"keywordsMax":15}`; the same account on DIAMOND
returns `{"entitiesUsed":0,"entitiesMax":100,"keywordsUsed":0,"keywordsMax":100}`.

**cURL example:**
```bash
curl -X GET http://localhost:8080/api/license/usage \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

**Status Codes:**
- `200 OK`
- `403 Forbidden` — no JWT supplied (or invalid token).
- `404 Not Found` — the user has no active license.

---

### L2. Get My License

**Endpoint:** `GET /api/licenses/me`

**Description:** The authenticated user's own license: their tier and the per-tier limits it grants.
Reports the **effective** tier — if an [offer-key override](#offer-key-overrides-effective-tier) is
active, the returned `tier` and limits are the overridden tier's. User-facing, so it returns **no
price**.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Response:**
```json
{
  "tier": "GOLD",
  "maxKeywords": 15,
  "maxEntities": 15,
  "maxMentionsPerMonth": 40000,
  "collectionFrequency": "PT1H"
}
```

> `collectionFrequency` is the ISO-8601 duration string for how often the ingestion pipeline collects
> mentions for the tier (e.g. `"PT24H"` for BRONZE, `"PT10M"` for DIAMOND).

**Status Codes:**
- `200 OK`
- `403 Forbidden` — no JWT supplied (or invalid token).
- `404 Not Found` — the user has no active license.

---

### L2a. Request a License

**Endpoint:** `POST /api/licenses/me`

**Description:** Self-service license request: the authenticated user picks a tier and a new **active**
license is issued to them. Any license they already held is deactivated first, so a user always has a
single active license. The new license never expires. Returns the generated license key — call
[`GET /api/licenses/me`](#l2-get-my-license) for the resulting tier and limits. User-facing, so it
carries **no price**.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "tier": "GOLD"
}
```

- `tier` — required; one of `BRONZE`, `SILVER`, `GOLD`, `DIAMOND`.

**Response:**
```json
{
  "licenseKey": "3f9a1c0b8e2d4f6a7c5b9e1d0a2f4c6b8d0e2f4a6c8b0d2e4f6a8c0b2d4e6f80"
}
```

- `licenseKey` — the generated key for the newly issued license (a 64-character SHA-256 hex string).

**cURL example:**
```bash
curl -X POST http://localhost:8080/api/licenses/me \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -H "Content-Type: application/json" \
  -d '{"tier": "GOLD"}'
```

**Status Codes:**
- `200 OK` — the license was issued; the new key is returned.
- `400 Bad Request` — `tier` is missing or not one of the valid tiers.
- `403 Forbidden` — no JWT supplied (or invalid token).

---

### L3. Redeem Offer Key

**Endpoint:** `POST /api/license/redeem-offer`

**Description:** Redeem an **offer key** to unlock a temporary tier override on top of the
authenticated user's existing license. A valid key sets the override (Diamond by default) on the
user's active license and increments the key's redemption count; from then on the user's
[effective tier](#offer-key-overrides-effective-tier) is the granted tier — raising both their limits
(F4/F5) and feature gates (F6) — until the override expires. The override inherits the key's own
expiry, so the elevated access ends when the key would have lapsed. Carries **no price**.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "code": "DIAMOND-LAUNCH-2026"
}
```

- `code` — required; the offer-key code to redeem.

**Response:**
```json
{
  "baseTier": "BRONZE",
  "overrideTier": "DIAMOND",
  "effectiveTier": "DIAMOND",
  "overrideExpiresAt": "2026-12-31T23:59:59Z"
}
```

**Response fields:**
- `baseTier` — the user's purchased (base) license tier, unchanged by the redemption.
- `overrideTier` — the tier the override now grants.
- `effectiveTier` — the tier now in force (the override while active).
- `overrideExpiresAt` — when the override lapses; `null` means it never expires on its own.

**cURL example:**
```bash
curl -X POST http://localhost:8080/api/license/redeem-offer \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE" \
  -H "Content-Type: application/json" \
  -d '{"code": "DIAMOND-LAUNCH-2026"}'
```

**Status Codes:**
- `200 OK` — redeemed; the override is now active.
- `400 Bad Request` — the key was rejected, with a structured, **price-free** body
  `{ reason, message }` where `reason` is one of `INVALID` (no such code), `INACTIVE` (deactivated),
  `EXPIRED` (past its expiry), or `EXHAUSTED` (redemption limit reached):
  ```json
  { "reason": "EXPIRED", "message": "This offer key has expired" }
  ```
- `403 Forbidden` — no JWT supplied (or invalid token).
- `404 Not Found` — the user has no active license to apply the override to.

---

### L4. List Premium Features

**Endpoint:** `GET /api/license/features`

**Description:** The full catalog of premium features with the **current user's entitlement** for each,
so the UI can render every feature up-front and lock-badge the ones the user's tier hasn't unlocked.
Entitlement uses the same rule as the feature gates: an admin is entitled to everything, otherwise the
[effective tier](#offer-key-overrides-effective-tier) must be at least the feature's `requiredTier`.
User-facing, so it carries **no price** (names the required tier, never its cost). See
[Premium Feature Tier Gating](#premium-feature-tier-gating) for how entitlement shapes each feature's
own response.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Response:** `200 OK`
```json
[
  { "key": "checkpoints",         "name": "Checkpoints",         "requiredTier": "SILVER",  "entitled": true },
  { "key": "crisis",              "name": "Crisis Management",   "requiredTier": "GOLD",    "entitled": false },
  { "key": "audience-content",    "name": "Audience & Content",  "requiredTier": "DIAMOND", "entitled": false },
  { "key": "intelligence-report", "name": "Intelligence Report", "requiredTier": "DIAMOND", "entitled": false },
  { "key": "aggregated-intel",    "name": "Aggregated Intel",    "requiredTier": "DIAMOND", "entitled": false }
]
```

**Response fields:**
- `key` — stable machine key the UI can switch on.
- `name` — human-readable feature name.
- `requiredTier` — the minimum tier that unlocks the feature.
- `entitled` — whether the current user may use it.

**Status Codes:**
- `200 OK`
- `403 Forbidden` — no JWT supplied (or invalid token).
- `404 Not Found` — the user has no active license (non-admin callers only; an admin is always entitled).

---

### L5. Audience & Content Module (Preview Placeholder)

**Endpoint:** `GET /api/audience-content`

**Description:** Placeholder for the Audience & Content premium module, which has not been built yet. This endpoint exists only so the `audience-content` feature (see [L4](#l4-list-premium-features)) has a real route to gate: the entire surface requires **DIAMOND**. It always returns `200` — an entitled caller gets a stub `data` payload, a caller below DIAMOND gets a masked `preview` instead — never `403`. Replace the placeholder payload with the real module when it ships.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Response:** Wrapped in an [`EntitledResponse`](#premium-feature-tier-gating) envelope.

Entitled (DIAMOND or admin):
```json
{
  "entitled": true,
  "requiredTier": "DIAMOND",
  "data": { "module": "audience-content", "status": "not-implemented" },
  "preview": null
}
```

Not entitled:
```json
{
  "entitled": false,
  "requiredTier": "DIAMOND",
  "data": null,
  "preview": { "module": "****************", "status": "***************" }
}
```

**Status Code:** `200 OK`

---

## Checkpoint Management APIs

Checkpoints mark significant dates for a managed entity (e.g., trailer release, opening weekend, award nomination). They are referenced by the sentiment-over-time, checkpoint-impact, and checkpoint-trend dashboard APIs to overlay milestones on sentiment charts.

> **Ownership:** Every checkpoint operation is scoped to the entity's owner. Creating or listing checkpoints for an entity, or updating/deleting a checkpoint, returns `404 Not Found` when the referenced entity does not exist **or** is owned by another user.

> **Tier-gated (SILVER).** Checkpoints (`/api/checkpoints/**`) require at least the **SILVER** tier; a
> BRONZE caller still gets `200 OK` with an `EntitledResponse` (`entitled=false` plus a masked
> `preview` for reads; a plain locked envelope with the mutation skipped for writes). Admins are always
> entitled. See [Premium Feature Tier Gating](#premium-feature-tier-gating).

The `checkpoints` table (like `graph_nodes`/`graph_edges`) is Hibernate-managed via `ddl-auto=update`
rather than the manual init script above, so it isn't in the `CREATE TABLE` block. Its `checkpoint_type`
column classifies each checkpoint as one of `TEASER`, `TRAILER`, `MUSIC_LAUNCH`, `PROMO_EVENT`,
`CAST_ANNOUNCEMENT`, `PRESS_MEET`, or `OTHER`, making the checkpoint a structured, queryable control
variable instead of freeform text. The column is nullable at the DB level so it can be added to an
already-populated table; a startup backfill (`CheckpointTypeBackfill`) then sets any legacy null
`checkpoint_type` to `OTHER`. On a fresh database it is always populated.

### 7a. Create Checkpoint

**Endpoint:** `POST /api/checkpoints`

**Description:** Create a new checkpoint for a managed entity.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "entityId": 1,
  "checkpointDate": "2026-03-15",
  "description": "Trailer Launch",
  "checkpointType": "TRAILER"
}
```

**Validation:**
- `entityId` — required.
- `checkpointDate` — required (ISO-8601 date).
- `description` — required, non-blank, max 20 characters.
- `checkpointType` — optional; one of `TEASER`, `TRAILER`, `MUSIC_LAUNCH`, `PROMO_EVENT`, `CAST_ANNOUNCEMENT`, `PRESS_MEET`, `OTHER`. Defaults to `OTHER` when omitted.

**Response:** Wrapped in an [`EntitledResponse`](#premium-feature-tier-gating) envelope.
```json
{
  "entitled": true,
  "requiredTier": "SILVER",
  "data": {
    "id": 10,
    "entityId": 1,
    "entityName": "The Quantum Paradox",
    "checkpointDate": "2026-03-15",
    "description": "Trailer Launch",
    "checkpointType": "TRAILER"
  },
  "preview": null
}
```

A caller below **SILVER** instead gets `200 OK` with `entitled: false`, `data: null`, and **no `preview`** — the checkpoint is **not** created (mutations are blocked, not blurred).

**Status Code:** `200 OK`

---

### 7b. List Checkpoints for Entity

**Endpoint:** `GET /api/checkpoints/entity/{entityId}`

**Description:** Retrieve all checkpoints for a managed entity.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` — Entity ID (e.g., 1)

**Response:** Wrapped in an [`EntitledResponse`](#premium-feature-tier-gating) envelope; the checkpoint list is the `data` field.
```json
{
  "entitled": true,
  "requiredTier": "SILVER",
  "data": [
    {
      "id": 10,
      "entityId": 1,
      "entityName": "The Quantum Paradox",
      "checkpointDate": "2026-03-15",
      "description": "Trailer Launch",
      "checkpointType": "TRAILER"
    },
    {
      "id": 11,
      "entityId": 1,
      "entityName": "The Quantum Paradox",
      "checkpointDate": "2026-04-01",
      "description": "Opening Weekend",
      "checkpointType": "PROMO_EVENT"
    }
  ],
  "preview": null
}
```

A caller below **SILVER** instead gets `entitled: false`, `data: null`, and a masked `preview` — the list is truncated to a single teaser element with every value blurred (numbers bucketed, strings starred), e.g.:
```json
{
  "entitled": false,
  "requiredTier": "SILVER",
  "data": null,
  "preview": [
    { "id": "a handful", "entityId": "a handful", "entityName": "★★★★★", "checkpointDate": "★★★★★", "description": "★★★★★", "checkpointType": "★★★★★" }
  ]
}
```

**Status Code:** `200 OK`

---

### 7c. Update Checkpoint

**Endpoint:** `PATCH /api/checkpoints/{checkpointId}`

**Description:** Update the date and/or description of an existing checkpoint. This is a partial update — include only the fields you want to change. Omitted fields are left unchanged.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `checkpointId` — Checkpoint ID (e.g., 10)

**Request Body:**
```json
{
  "checkpointDate": "2026-03-20",
  "description": "Trailer v2",
  "checkpointType": "TRAILER"
}
```

**Field Rules:**
- `checkpointDate` — optional (ISO-8601 date). When provided, must not collide with another checkpoint for the same entity (entity + date is unique).
- `description` — optional; when provided, must be non-blank and at most 20 characters.
- `checkpointType` — optional; one of `TEASER`, `TRAILER`, `MUSIC_LAUNCH`, `PROMO_EVENT`, `CAST_ANNOUNCEMENT`, `PRESS_MEET`, `OTHER`. Left unchanged when omitted.

**Response:** Wrapped in an [`EntitledResponse`](#premium-feature-tier-gating) envelope; the updated checkpoint is the `data` field.
```json
{
  "entitled": true,
  "requiredTier": "SILVER",
  "data": {
    "id": 10,
    "entityId": 1,
    "entityName": "The Quantum Paradox",
    "checkpointDate": "2026-03-20",
    "description": "Trailer v2",
    "checkpointType": "TRAILER"
  },
  "preview": null
}
```

A caller below **SILVER** gets `entitled: false`, `data: null`, **no `preview`**, and the update is **not** applied.

**Status Code:** `200 OK`

**Errors:**
- `400 Bad Request` — checkpoint not found, validation failure (description too long/blank), or the new date already has a checkpoint for the entity.

---

### 7d. Delete Checkpoint

**Endpoint:** `DELETE /api/checkpoints/{checkpointId}`

**Description:** Delete a checkpoint by ID.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `checkpointId` — Checkpoint ID (e.g., 10)

**Response:** Wrapped in an [`EntitledResponse`](#premium-feature-tier-gating) envelope; `data` is `null` (a delete has no payload).
```json
{ "entitled": true, "requiredTier": "SILVER", "data": null, "preview": null }
```

A caller below **SILVER** gets `entitled: false` (and `data`/`preview` both `null`) and the delete is **not** applied.

**Status Code:** `200 OK`

---

## Dashboard APIs

> **Ownership:** Every dashboard endpoint is owner-scoped. Any `{entityId}` (or each id in an `entityIds` cluster list) must belong to the authenticated user; otherwise the request returns `404 Not Found` — the same response as for a non-existent entity, so existence is never leaked.
>
> **Admin:** An admin (`ROLE_ADMIN`) may access any user's entities here. The mention-list endpoints (`GET /api/dashboard/{entityId}/mentions` and `GET /api/dashboard/cluster/mentions`) additionally accept an optional `ownerId` query parameter: for an admin it requires the referenced entity(ies) to belong to that user (else `404`); a non-admin who supplies `ownerId` gets `403 Forbidden`.

### 8. Get Entity Statistics

**Endpoint:** `GET /api/dashboard/{entityId}/stats`

**Description:** Get core statistics for an entity

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - Entity ID (e.g., 1)

**Response:**
```json
{
  "totalMentions": 50,
  "positiveSentiment": 0.64,
  "negativeSentiment": 0.18,
  "neutralSentiment": 0.18
}
```

**Status Code:** `200 OK`

**Side effect:** Each successful call upserts a row in `user_entity_views` recording that the authenticated user viewed `entityId` at the current server time. The timestamp is exposed via `GET /api/dashboard/{entityId}/last-seen`.

---

### 8a. Get Last-Seen Timestamp

**Endpoint:** `GET /api/dashboard/{entityId}/last-seen`

**Description:** Return the most recent time the authenticated user opened the dashboard for `entityId` (set automatically by `/stats` and `/mentions`). Useful for flagging "new since you last looked" mentions in the UI.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - Entity ID (e.g., 1)

**Response (user has previously viewed the entity):**
```json
{
  "lastSeenAt": "2026-05-21T09:30:00Z"
}
```

**Response (no prior view recorded):**
```json
{
  "lastSeenAt": null
}
```

**Status Codes:**
- `200 OK` — `lastSeenAt` is either an ISO-8601 instant or `null`
- `403 Forbidden` — no JWT supplied (or invalid token); Spring Security rejects unauthenticated dashboard requests at the filter level

---

### 8b. Get What's Changed Since Last Visit

**Endpoint:** `GET /api/dashboard/{entityId}/whats-changed`

**Description:** Return a summary of how an entity's sentiment landscape has shifted since the authenticated user last viewed it (as tracked in `user_entity_views`). Designed to power "new since you last looked" callouts on the dashboard.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - Entity ID (e.g., 1)

**Response fields:**
- `sentiment_score_delta` — current `netSentimentScore` (positive / negative mention counts) minus the same score computed against only mentions with `postDate <= lastSeenAt`.
- `new_mentions_count` — mentions whose `postDate > lastSeenAt`.
- `new_negative_count` — same filter, restricted to `sentiment = NEGATIVE`.
- `new_super_spreader_count` — distinct authors who (a) posted about this entity since `lastSeenAt`, (b) had no prior mentions for this entity at or before `lastSeenAt`, AND (c) appear in the top-spreaders list for at least one of the entity's keywords (resolved via the AuraMath proxy, same source as `INFLUENCER_NEGATIVE` alerts).
- `competitor_delta` — map of competitor name to `sentiment_score_delta` computed for that competitor against the same `lastSeenAt` cutoff.

**Response (user has previously viewed the entity):**

Example below is a real response for entity 6 (`Parasakthi`) with `lastSeenAt` backdated to `2026-01-01T00:00:00Z`. The user has seen 1902 new mentions since that timestamp, 772 of which are negative; the entity's overall positive-to-negative ratio has dropped by ~0.74, while both tracked competitors have climbed:

```json
{
  "sentiment_score_delta": -0.7391025641025641,
  "new_mentions_count": 1902,
  "new_negative_count": 772,
  "new_super_spreader_count": 0,
  "competitor_delta": {
    "With Love": 2.5913621262458473,
    "Dhurandhar2": 2.3970588235294117
  }
}
```

**Response (first visit — no `lastSeenAt` recorded yet):**
```json
{}
```

All five fields are omitted (null) together when the user has no prior view of `entityId`, the user can't be resolved, or the entity doesn't exist.

**cURL example:**
```bash
curl -X GET http://localhost:8080/api/dashboard/6/whats-changed \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

**Status Codes:**
- `200 OK`
- `403 Forbidden` — no JWT supplied (or invalid token); Spring Security rejects unauthenticated dashboard requests at the filter level

---

### 8c. Get What's New (Reward Cards)

**Endpoint:** `GET /api/dashboard/{entityId}/whats-new`

**Description:** Return a short, prioritized list of "reward cards" derived from the same `whats-changed` deltas, designed to give the dashboard a fast hit of variable, dopaminergic feedback on open. At most 5 cards are returned; within each priority tier the order is randomized so two consecutive opens with identical underlying state still feel slightly different.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - Entity ID (e.g., 1)

**Card shape:**
- `kind` — discriminator: `COMPETITOR_DROP`, `NEW_POSITIVE_SUPER_SPREADER`, `SENTIMENT_RISE`, or `NEGATIVE_SPIKE`.
- `headline` — human-readable one-liner suitable for direct rendering.
- `value` — the signed magnitude behind the card. For `COMPETITOR_DROP` and `SENTIMENT_RISE` it's the score delta; for `NEGATIVE_SPIKE` it's the new negative-mention count; for `NEW_POSITIVE_SUPER_SPREADER` it's the number of positive mentions cited as evidence.
- `evidence_mention_ids` — up to 3 mention IDs that back this card, suitable for deep-linking into `/api/dashboard/{entityId}/mentions`.

**Card priority (highest first):**
1. **`COMPETITOR_DROP`** — one card per competitor whose `sentiment_score_delta` (same definition as `whats-changed`) fell by more than `0.05` since `lastSeenAt`. Evidence: up to 3 most-recent `NEGATIVE` mentions of the competitor after `lastSeenAt`.
2. **`NEW_POSITIVE_SUPER_SPREADER`** — one card per author who (a) posted about this entity since `lastSeenAt`, (b) had no prior mentions for this entity at or before `lastSeenAt`, (c) appears in the top-spreaders list for at least one of the entity's keywords (resolved via the AuraMath proxy, same source as `whats-changed`), AND (d) has at least one `POSITIVE` mention in the window. Evidence: those positive mentions.
3. **`SENTIMENT_RISE`** — single card when the entity's own `sentiment_score_delta` is greater than `+0.05`. Evidence: up to 3 most-recent `POSITIVE` mentions after `lastSeenAt`.
4. **`NEGATIVE_SPIKE`** — single card when at least 5 new `NEGATIVE` mentions arrived since `lastSeenAt`. Evidence: up to 3 most-recent `NEGATIVE` mentions after `lastSeenAt`. (Intentionally last so it can re-trigger the alert/crisis flow without dominating the reward feed.)

The endpoint collects all qualifying cards in priority order, shuffles ties within each tier, and returns the first 5.

**Response (user has previously viewed the entity):**

Example below is a representative shape for an entity whose two tracked competitors have both slipped, a new top-spreader is posting positively, and overall sentiment has climbed:

```json
[
  {
    "kind": "COMPETITOR_DROP",
    "headline": "With Love's sentiment dropped 2.59 since your last visit",
    "value": -2.591362126245847,
    "evidence_mention_ids": [48211, 48207, 48199]
  },
  {
    "kind": "COMPETITOR_DROP",
    "headline": "Dhurandhar2's sentiment dropped 2.40 since your last visit",
    "value": -2.397058823529412,
    "evidence_mention_ids": [49102, 49098]
  },
  {
    "kind": "NEW_POSITIVE_SUPER_SPREADER",
    "headline": "@cinephile_arjun — a new super-spreader — is posting positively about Parasakthi",
    "value": 2.0,
    "evidence_mention_ids": [50314, 50318]
  },
  {
    "kind": "SENTIMENT_RISE",
    "headline": "Sentiment climbed 0.41 since your last visit",
    "value": 0.4129032258064516,
    "evidence_mention_ids": [50301, 50299, 50288]
  }
]
```

**Response (first visit — no `lastSeenAt` recorded yet, or no cards qualify):**
```json
[]
```

An empty array is returned when the user has no prior view of `entityId`, the user can't be resolved, the entity doesn't exist, or no delta crossed any of the card thresholds.

**cURL example:**
```bash
curl -X GET http://localhost:8080/api/dashboard/6/whats-new \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

**Status Codes:**
- `200 OK`
- `403 Forbidden` — no JWT supplied (or invalid token); Spring Security rejects unauthenticated dashboard requests at the filter level

---

### 9. Get Cluster Statistics

**Endpoint:** `GET /api/dashboard/cluster/stats`

**Description:** Get core statistics for a cluster of entities.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `entityIds` - Comma-separated list of entity IDs (e.g., 1,2,3)

**Response:**
```json
{
  "totalMentions": 150,
  "positiveSentiment": 0.60,
  "negativeSentiment": 0.20,
  "neutralSentiment": 0.20
}
```

**Status Code:** `200 OK`

---

### 10. Get Average Entity Statistics for Multiple Entities

**Endpoint:** `GET /api/dashboard/cluster/stats/avg`

**Description:** Get average statistics for multiple entities based on the intersection of their data.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `entityIds` - Comma-separated list of entity IDs (e.g., 1,2,3)

**Response:**
```json
{
  "totalMentions": 150,
  "overallSentiment": 0.73,
  "positiveRatio": 0.61,
  "netSentimentScore": 3.35
}
```

**Status Code:** `200 OK`

---

### 11. Get Average Entity Statistics

**Endpoint:** `GET /api/dashboard/{entityId}/stats/avg`

**Description:** Get average statistics for an entity including overall sentiment, positive ratio, and net sentiment score.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - Entity ID (e.g., 1)

**Response:**
```json
{
  "totalMentions": 50,
  "overallSentiment": 0.75,
  "positiveRatio": 0.64,
  "netSentimentScore": 3.55
}
```

**Status Code:** `200 OK`

---

### 12. Get Competitor Snapshot

**Endpoint:** `GET /api/dashboard/{entityId}/competitor-snapshot`

**Description:** Get statistics for the entity and its competitors

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - Entity ID (e.g., 1)

**Response:**
```json
[
  {
    "entityName": "The Quantum Paradox",
    "totalMentions": 50,
    "overallSentiment": 0.75,
    "positiveRatio": 0.64,
    "netSentimentScore": 3.55
  },
  {
    "entityName": "Inception 2",
    "totalMentions": 50,
    "overallSentiment": 0.72,
    "positiveRatio": 0.58,
    "netSentimentScore": 3.15
  },
  {
    "entityName": "Interstellar Reloaded",
    "totalMentions": 50,
    "overallSentiment": 0.74,
    "positiveRatio": 0.62,
    "netSentimentScore": 3.35
  }
]
```

**Status Code:** `200 OK`

---

### 13. Get Sentiment Over Time

**Endpoint:** `GET /api/dashboard/sentiment-over-time`

**Description:** Get time-series data for sentiment analysis

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `period` - Time period (DAY, DAY15, DAY30, WEEK, or MONTH)
- `entityIds` - Comma-separated list of entity IDs to compare (e.g., 1,3,4)

**Example Request:**
```
GET /api/dashboard/sentiment-over-time?period=WEEK&entityIds=1,3
```

**Response:**
```json
{
  "entities": [
    {
      "name": "The Quantum Paradox",
      "sentiments": [
        {
          "date": "2025-W44",
          "positive": 8,
          "negative": 2,
          "neutral": 1
        },
        {
          "date": "2025-W45",
          "positive": 12,
          "negative": 3,
          "neutral": 2
        },
        {
          "date": "2025-W46",
          "positive": 15,
          "negative": 4,
          "neutral": 3
        }
      ],
      "checkpoints": [
        {
          "date": "2025-W45",
          "description": "Trailer Launch"
        }
      ]
    },
    {
      "name": "Inception 2",
      "sentiments": [
        {
          "date": "2025-W44",
          "positive": 7,
          "negative": 3,
          "neutral": 0
        },
        {
          "date": "2025-W45",
          "positive": 10,
          "negative": 5,
          "neutral": 1
        },
        {
          "date": "2025-W46",
          "positive": 12,
          "negative": 2,
          "neutral": 2
        }
      ],
      "checkpoints": []
    }
  ]
}
```

**Response fields:**
- `checkpoints` — list of checkpoint markers for the entity, each with a `date` (formatted to match the requested `period` bucket, e.g. `"2025-W45"` for `WEEK`, `"2025-11-03"` for `DAY`) and `description`. Empty array if the entity has no checkpoints. Checkpoints are sourced from the Checkpoint Management APIs.

**Status Code:** `200 OK`

---

### 13a. Get Sentiment Over Time (Date Range)

**Endpoint:** `GET /api/dashboard/sentiment-over-time-range`

**Description:** Get time-series sentiment data for an explicit `startDate`/`endDate` window, instead of the fixed lookback windows used by `period` in [Get Sentiment Over Time](#13-get-sentiment-over-time). The bucket size is chosen automatically from the length of the range:
- Up to 90 days → daily buckets
- Up to 365 days → weekly buckets
- Longer than 365 days → monthly buckets

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `startDate` - Start of the range, inclusive (ISO date, e.g. `2025-10-01`)
- `endDate` - End of the range, inclusive (ISO date, e.g. `2025-12-31`)
- `entityIds` - Comma-separated list of entity IDs to compare (e.g., 1,3,4)

**Example Request:**
```
GET /api/dashboard/sentiment-over-time-range?startDate=2025-10-01&endDate=2025-12-31&entityIds=1,3
```

**Response:** Same shape as [Get Sentiment Over Time](#13-get-sentiment-over-time) — a `SentimentOverTimeResponse` with per-entity `sentiments` (bucketed by the inferred granularity) and `checkpoints` (dates formatted to match that same granularity, e.g. `"2025-11-03"` for daily buckets, `"2025-W45"` for weekly, `"2025-11"` for monthly).

**Validation:** Returns `400 Bad Request` if `startDate` is after `endDate`.

**Status Code:** `200 OK`

---

### 14. Get Platform Mentions

**Endpoint:** `GET /api/dashboard/{entityId}/platform-mentions`

**Description:** Get mention counts broken down by platform

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - Entity ID (e.g., 1)

**Response:**
```json
{
    "INSTAGRAM": {
        "POSITIVE": 37,
        "NEGATIVE": 1,
        "NEUTRAL": 3
    },
    "REDDIT": {
        "POSITIVE": 13,
        "NEGATIVE": 23,
        "NEUTRAL": 14
    },
    "X": {
        "POSITIVE": 43,
        "NEGATIVE": 49,
        "NEUTRAL": 9
    },
    "YOUTUBE": {
        "POSITIVE": 212,
        "NEGATIVE": 53,
        "NEUTRAL": 13
    }
}
```

**Status Code:** `200 OK`

---

### 15. Get Platform Mentions for a Cluster

**Endpoint:** `GET /api/dashboard/cluster/platform-mentions`

**Description:** Get mention counts for a cluster of entities, broken down by platform. This is based on the intersection of data for the given entities.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Request Body:**
```json
[1, 2, 3]
```

**Response:**
```json
{
    "INSTAGRAM": {
        "POSITIVE": 10,
        "NEGATIVE": 2,
        "NEUTRAL": 1
    },
    "REDDIT": {
        "POSITIVE": 5,
        "NEGATIVE": 8,
        "NEUTRAL": 4
    },
    "X": {
        "POSITIVE": 15,
        "NEGATIVE": 12,
        "NEUTRAL": 3
    },
    "YOUTUBE": {
        "POSITIVE": 50,
        "NEGATIVE": 10,
        "NEUTRAL": 5
    }
}
```

**Status Code:** `200 OK`

---

### 16. Get Filtered Mentions

**Endpoint:** `GET /api/dashboard/{entityId}/mentions`

**Description:** Get a paginated list of mentions with optional filters. The results are sorted by time, with the latest mentions appearing first.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - Entity ID (e.g., 1)

**Query Parameters:**
- `platform` - Filter by platform (X, REDDIT, YOUTUBE, INSTAGRAM) - Optional
- `page` - Page number (default: 0)
- `size` - Page size (default: all mentions are returned if not specified)
- `ownerId` — (admin only) require `entityId` to belong to this user; a non-admin who supplies it gets `403 Forbidden`. Optional.

**Example Request:**
```
GET /api/dashboard/1/mentions?platform=X&page=0&size=5
```

**Response:**
```json
{
  "content": [
    {
      "id": 1,
      "managedEntityId": 1,
      "platform": "X",
      "postId": "The_Quantum_Paradox_post_0",
      "content": "This movie is absolutely amazing! Best film of the year!",
      "author": "movie_fan_123",
      "postDate": "2025-11-05T10:30:00Z",
      "sentiment": "POSITIVE",
      "impressions": "15230",
      "available_actions": ["draft-reply", "escalate", "mobilize", "report-abuse"],
      "action_history_summary": {
        "drafts": 1,
        "posted": 0,
        "escalated": false
      }
    },
    {
      "id": 2,
      "managedEntityId": 1,
      "platform": "X",
      "postId": "The_Quantum_Paradox_post_5",
      "content": "Incredible performance! Oscar-worthy for sure.",
      "author": "critic_sarah",
      "postDate": "2025-11-03T14:20:00Z",
      "sentiment": "POSITIVE",
      "impressions": "8741",
      "available_actions": ["draft-reply", "escalate", "mobilize", "report-abuse"],
      "action_history_summary": {
        "drafts": 0,
        "posted": 0,
        "escalated": false
      }
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 5
  },
  "totalElements": 15,
  "totalPages": 3,
  "last": false
}
```

**Per-mention `impressions` field:**
- `impressions` — number of times the post was displayed on its source platform, returned as a string (e.g. `"15230"`). Only X currently exposes an impression metric (sourced from the ingestion pipeline's `x_posts.views_count`, batch-fetched per page); mentions on `REDDIT`, `INSTAGRAM` and `YOUTUBE` return `"NA"`, as do X mentions with no matching `x_posts` row. The same field is present on every `MentionResponse` returned by the mention-action endpoints (sections 23–26).

**Per-mention action fields:**
- `available_actions` — the full set of inline actions the UI can offer for any mention. Always `["draft-reply", "escalate", "mobilize", "report-abuse"]`; emitted on every mention so the frontend doesn't need a separate config call.
- `action_history_summary` — rollup of prior actions taken against this specific mention, used to dim buttons whose effect has already been applied without an N+1 round-trip to `/api/mentions/{id}/actions`.
  - `drafts` — number of `ReplyDraft` rows for this mention (any status).
  - `posted` — subset of those drafts whose status is `POSTED`.
  - `escalated` — `true` iff at least one `CrisisPlan` row exists for this mention.

**Status Code:** `200 OK`

**Side effect:** Each successful call upserts the authenticated user's `last_seen_at` for `entityId` in `user_entity_views` (same row touched by `/{entityId}/stats`). Read it back via `GET /api/dashboard/{entityId}/last-seen`.

---

### 17. Get Filtered Mentions for a Cluster

**Endpoint:** `GET /api/dashboard/cluster/mentions`

**Description:** Get a paginated list of mentions for a cluster of entities, based on the intersection of their data. The results are sorted by time, with the latest mentions appearing first.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `entityIds` - Comma-separated list of entity IDs (e.g., 1,2,3)
- `platform` - Filter by platform (X, REDDIT, YOUTUBE, INSTAGRAM) - Optional
- `page` - Page number (default: 0)
- `size` - Page size (default: all mentions are returned if not specified)
- `ownerId` — (admin only) require every id in `entityIds` to belong to this user; a non-admin who supplies it gets `403 Forbidden`. Optional.

**Example Request:**
```
GET /api/dashboard/cluster/mentions?entityIds=1,2&platform=X&page=0&size=5
```

**Response:**
```json
{
  "content": [
    {
      "id": 1,
      "managedEntityId": 1,
      "platform": "X",
      "postId": "The_Quantum_Paradox_post_0",
      "content": "This movie is absolutely amazing! Best film of the year!",
      "author": "movie_fan_123",
      "postDate": "2025-11-05T10:30:00Z",
      "sentiment": "POSITIVE",
      "impressions": "15230",
      "available_actions": ["draft-reply", "escalate", "mobilize", "report-abuse"],
      "action_history_summary": {
        "drafts": 1,
        "posted": 0,
        "escalated": false
      }
    },
    {
      "id": 2,
      "managedEntityId": 1,
      "platform": "X",
      "postId": "The_Quantum_Paradox_post_5",
      "content": "Incredible performance! Oscar-worthy for sure.",
      "author": "critic_sarah",
      "postDate": "2025-11-03T14:20:00Z",
      "sentiment": "POSITIVE",
      "impressions": "8741",
      "available_actions": ["draft-reply", "escalate", "mobilize", "report-abuse"],
      "action_history_summary": {
        "drafts": 0,
        "posted": 0,
        "escalated": false
      }
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 5
  },
  "totalElements": 15,
  "totalPages": 3,
  "last": false
}
```

Each mention carries the same `impressions`, `available_actions` and `action_history_summary` fields as the single-entity endpoint above — see section 16 for the field semantics.

**Status Code:** `200 OK`

---

### 18. Get Hourly Activity Distribution

**Endpoint:** `GET /api/dashboard/{entityId}/hourly-activity`

**Description:** Get the per-day, hour-of-day distribution (0-23) of distinct active users for an entity over a given period, plus a period-wide aggregate. Optionally narrowed by language, industry, or state tags from `entity_keywords`. Powers the "best time to post" heatmap for the marketing team.

A mention counts toward the distribution when its `content` matches (case-insensitive `ILIKE`) one of the entity's keywords whose `language`/`industry`/`state` matches every supplied filter. With no filters, all of the entity's keywords are considered. All dates and hours are in **UTC**.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Query Parameters:**
- `period` (required) - `DAY` (last 7 days), `DAY15` (15 days), `DAY30` (30 days), `WEEK` (last 12 weeks), `MONTH` (last 12 months), or `MONTH6` (last 6 months)
- `language` (optional) - Filter to keywords tagged with this language (e.g. `tamil`, `english`)
- `industry` (optional) - Filter to keywords tagged with this industry (e.g. `Kollywood`)
- `state` (optional) - Filter to keywords tagged with this state

**Example Request:**
```
GET /api/dashboard/21/hourly-activity?period=DAY
```

**Response (truncated for readability):**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "period": "DAY",
  "startDate": "2026-05-10T12:00:00Z",
  "endDate": "2026-05-17T12:00:00Z",
  "language": null,
  "industry": null,
  "state": null,
  "totalActiveUsers": 0,
  "hourlyDistribution": {
    "0": 0, "1": 0, "2": 0, "3": 0, "4": 0, "5": 0,
    "6": 0, "7": 0, "8": 0, "9": 0, "10": 0, "11": 0,
    "12": 0, "13": 0, "14": 0, "15": 0, "16": 0, "17": 0,
    "18": 0, "19": 0, "20": 0, "21": 0, "22": 0, "23": 0
  },
  "dailyDistribution": {
    "2026-05-10": { "0": 0, "1": 0, "...": "...", "22": 1, "23": 0 },
    "2026-05-11": { "0": 0, "1": 0, "...": "...", "23": 0 },
    "2026-05-12": { "0": 0, "1": 0, "...": "...", "23": 0 },
    "2026-05-13": { "0": 0, "1": 0, "...": "...", "23": 0 },
    "2026-05-14": { "0": 0, "1": 0, "...": "...", "23": 0 },
    "2026-05-15": { "0": 0, "1": 0, "...": "...", "23": 0 },
    "2026-05-16": { "0": 0, "1": 0, "...": "...", "23": 0 },
    "2026-05-17": { "0": 0, "1": 0, "...": "...", "23": 0 }
  }
}
```

**Response fields:**
- `hourlyDistribution` — period-wide aggregate: distinct active authors per hour-of-day (0-23) across the entire window. Always a complete 0-23 map.
- `dailyDistribution` — per-day breakdown: every calendar day in the `[startDate, endDate]` window mapped to a complete 0-23 hour map. Each value is the count of distinct active authors for that specific day-hour. The number of days returned matches the period: ~8 for `DAY`, ~16 for `DAY15`, ~31 for `DAY30`, ~85 for `WEEK`, ~182 for `MONTH6`, ~366 for `MONTH`.
- `totalActiveUsers` — distinct authors across the whole window. Not the sum of the buckets (an author active in multiple hours/days is counted once here, but in every bucket they appear in).

**Notes:**
- All hour/day bucketing uses UTC, matching the `startDate`/`endDate` fields.
- Every day in the window is present in `dailyDistribution`, even days with zero activity (the UI can render a heatmap directly without filling gaps).
- If no filters match any of the entity's keywords, all buckets and `totalActiveUsers` are zero.
- Response size grows linearly with the period: `MONTH6` returns ~4,300 day-hour entries, `MONTH` returns ~8,800.

**Status Code:** `200 OK`

---

### 18a. Get Sentiment Delta

**Endpoint:** `GET /api/dashboard/{entityId}/sentiment-delta`

**Description:** Compare sentiment metrics between two dates. For each date, the service computes metrics over a window of `windowDays` days ending on (and including) that date, then returns the delta between the two windows.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` — Entity ID (e.g., 1)

**Query Parameters:**
- `fromDate` (required) — Start date (ISO-8601, e.g., `2026-03-01`)
- `toDate` (required) — End date (ISO-8601, e.g., `2026-03-15`). Must be after `fromDate`.
- `windowDays` (optional, default: `7`) — Number of days in each comparison window. Must be 1–30.

**Example Request:**
```
GET /api/dashboard/1/sentiment-delta?fromDate=2026-03-01&toDate=2026-03-15&windowDays=7
```

**Response:**
```json
{
  "fromDate": "2026-03-01",
  "toDate": "2026-03-15",
  "fromLabel": "2026-02-23 to 2026-03-01",
  "toLabel": "2026-03-09 to 2026-03-15",
  "fromTotalMentions": 42,
  "toTotalMentions": 58,
  "mentionsDelta": 16,
  "fromPositiveRatio": 0.62,
  "toPositiveRatio": 0.71,
  "positiveRatioDelta": 0.09,
  "fromNetSentiment": 2.15,
  "toNetSentiment": 3.40,
  "netSentimentDelta": 1.25
}
```

**Status Codes:**
- `200 OK`
- `400 Bad Request` — `fromDate` is not before `toDate`, or `windowDays` is outside 1–30

---

### 18b. Get Checkpoint Impact

**Endpoint:** `GET /api/dashboard/{entityId}/checkpoint-impact`

**Description:** Compute before/after sentiment metrics for every checkpoint of an entity. For each checkpoint, the service compares a window of `windowDays` days before the checkpoint date with `windowDays` days after it, and reports the change in positive ratio and net sentiment.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` — Entity ID (e.g., 1)

**Query Parameters:**
- `windowDays` (optional, default: `7`) — Number of days in the before/after window. Must be 1–30.

**Example Request:**
```
GET /api/dashboard/1/checkpoint-impact?windowDays=7
```

**Response:**
```json
{
  "entityId": 1,
  "entityName": "The Quantum Paradox",
  "windowDays": 7,
  "impacts": [
    {
      "checkpointId": 10,
      "checkpointDate": "2026-03-15",
      "description": "Trailer Launch",
      "beforeTotalMentions": 42,
      "afterTotalMentions": 78,
      "beforePositiveRatio": 0.62,
      "afterPositiveRatio": 0.74,
      "positiveRatioChange": 0.12,
      "beforeNetSentiment": 2.15,
      "afterNetSentiment": 4.10,
      "netSentimentChange": 1.95,
      "impactDirection": "IMPROVED"
    },
    {
      "checkpointId": 11,
      "checkpointDate": "2026-04-01",
      "description": "Opening Weekend",
      "beforeTotalMentions": 65,
      "afterTotalMentions": 55,
      "beforePositiveRatio": 0.70,
      "afterPositiveRatio": 0.58,
      "positiveRatioChange": -0.12,
      "beforeNetSentiment": 3.50,
      "afterNetSentiment": 1.80,
      "netSentimentChange": -1.70,
      "impactDirection": "DECLINED"
    }
  ]
}
```

**Response fields:**
- `impacts` — one entry per checkpoint, in checkpoint-date order.
- `impactDirection` — one of `IMPROVED`, `DECLINED`, `STABLE`, derived from the net sentiment change.

**Status Codes:**
- `200 OK`
- `400 Bad Request` — `windowDays` is outside 1–30

---

### 18c. Get Checkpoint Trend

**Endpoint:** `GET /api/dashboard/{entityId}/checkpoint-trend`

**Description:** Return time-series sentiment metrics at each checkpoint date for an entity. Each trend point includes cumulative and period mention counts, positive ratio, and net sentiment, plus the change from the previous checkpoint.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` — Entity ID (e.g., 1)

**Example Request:**
```
GET /api/dashboard/1/checkpoint-trend
```

**Response:**
```json
{
  "entityId": 1,
  "entityName": "The Quantum Paradox",
  "trendPoints": [
    {
      "checkpointDate": "2026-03-15",
      "description": "Trailer Launch",
      "cumulativeMentions": 120,
      "periodMentions": 120,
      "positiveRatio": 0.65,
      "netSentiment": 2.80,
      "positiveRatioChangeFromPrevious": null,
      "netSentimentChangeFromPrevious": null
    },
    {
      "checkpointDate": "2026-04-01",
      "description": "Opening Weekend",
      "cumulativeMentions": 245,
      "periodMentions": 125,
      "positiveRatio": 0.72,
      "netSentiment": 3.50,
      "positiveRatioChangeFromPrevious": 0.07,
      "netSentimentChangeFromPrevious": 0.70
    }
  ]
}
```

**Response fields:**
- `trendPoints` — one entry per checkpoint, in checkpoint-date order.
- `cumulativeMentions` — total mentions from the beginning up to and including this checkpoint date.
- `periodMentions` — mentions between the previous checkpoint date (exclusive) and this checkpoint date (inclusive). For the first checkpoint, this equals `cumulativeMentions`.
- `positiveRatioChangeFromPrevious` / `netSentimentChangeFromPrevious` — `null` for the first trend point; the delta from the previous checkpoint for subsequent points.

**Status Code:** `200 OK`

---

### 18d. Get Audience Pulse (Top Regions by Buzz)

**Endpoint:** `GET /api/dashboard/{entityId}/audience-pulse`

**Description:** Rank the regions an entity is being talked about in, by buzz (raw post/comment count). Backs the "Audience Pulse — Top Regions by Buzz" panel on the Command Center UI.

The region for each post/comment comes from the `predicted_region` column the ingestion pipeline stamps on the raw platform tables (`x_posts`, `youtube_comments`, `reddit_posts`, `instagram_posts`) — there is no separate region table. Each of the four tables is joined back to the entity through `mentions`/`mention_entities` (the same linkage every other mention-scoped dashboard query uses), unioned together, and grouped by region. Rows the pipeline predicted as **`irrelevant`** (case-insensitive), or left with a `null` region, are excluded before ranking — they never count toward `totalMentions` or appear in `regions`.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/audience-pulse
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "totalMentions": 187,
  "regions": [
    { "rank": 1, "region": "Tamil Nadu", "mentionCount": 96, "sharePct": 51.34 },
    { "rank": 2, "region": "Karnataka", "mentionCount": 54, "sharePct": 28.88 },
    { "rank": 3, "region": "Maharashtra", "mentionCount": 37, "sharePct": 19.78 }
  ]
}
```

**Response fields:**
- `totalMentions` — sum of `mentionCount` across all returned regions (i.e. every non-`irrelevant`, non-null-region post/comment linked to the entity, across all four platforms).
- `regions` — ranked highest-buzz-first; empty when the entity has no region-tagged posts yet.
- `regions[].rank` — 1-based position after sorting by `mentionCount` descending.
- `regions[].mentionCount` — raw post/comment count for that region (buzz), summed across all four platforms.
- `regions[].sharePct` — `mentionCount` as a percentage of `totalMentions` (0 when `totalMentions` is 0).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18e. Get Promotional Mix (Organic vs. Promotional Buzz)

**Endpoint:** `GET /api/dashboard/{entityId}/promotional-mix`

**Description:** Splits an entity's buzz into promotional (`is_promotional = true`) vs. organic posts/comments. Tells the marketing team how much of the current conversation is being driven by paid/official promotion versus genuine word-of-mouth — a high organic share means the campaign has real pull and paid spend can be redirected; a low one means visibility is still manufactured and needs more push.

`is_promotional` is a not-null boolean stamped by the ingestion pipeline on all four raw platform tables (`x_posts`, `youtube_comments`, `reddit_posts`, `instagram_posts`), joined back to the entity via `mentions`/`mention_entities` the same way `audience-pulse` joins `predicted_region`. Unlike the other classification columns there is no NULL/`irrelevant` value to exclude — every post counts as either promotional or organic.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/promotional-mix
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "totalPosts": 412,
  "promotionalCount": 47,
  "organicCount": 365,
  "promotionalSharePct": 11.41
}
```

**Response fields:**
- `totalPosts` — every post/comment linked to the entity across all four platforms (`promotionalCount + organicCount`).
- `promotionalSharePct` — `promotionalCount` as a percentage of `totalPosts` (0 when `totalPosts` is 0).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18f. Get Author Type Breakdown (Who's Talking)

**Endpoint:** `GET /api/dashboard/{entityId}/author-type-breakdown`

**Description:** Ranks the entity's buzz by `author_type` — `general_public`, `fan_page`, `media_press`, `official_studio`, `verified_celebrity_influencer`, `bot_spam`, etc. Tells the marketing team the composition of voices driving conversation, so they can decide whether to invest in press/PR outreach, influencer partnerships, or fan-community activation — and how much of the volume is noise (`bot_spam`) that shouldn't be read as real signal.

Same join pattern as `audience-pulse`/`promotional-mix`: `author_type` lives only on the raw per-platform tables, joined back to `mentions`/`mention_entities` via `post_id` + `platform`. Rows classified `irrelevant` (case-insensitive) or left `NULL` (not yet enriched by the classification pipeline) are excluded before ranking.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/author-type-breakdown
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "totalClassifiedPosts": 298,
  "authorTypes": [
    { "rank": 1, "authorType": "general_public", "count": 201, "sharePct": 67.45 },
    { "rank": 2, "authorType": "fan_page", "count": 48, "sharePct": 16.11 },
    { "rank": 3, "authorType": "media_press", "count": 27, "sharePct": 9.06 },
    { "rank": 4, "authorType": "verified_celebrity_influencer", "count": 14, "sharePct": 4.70 },
    { "rank": 5, "authorType": "bot_spam", "count": 8, "sharePct": 2.68 }
  ]
}
```

**Response fields:**
- `totalClassifiedPosts` — sum of `count` across all returned author types (i.e. every non-`irrelevant`, non-null-`author_type` post/comment linked to the entity). Excludes not-yet-classified rows, so this can be smaller than the entity's total mention count.
- `authorTypes` — ranked highest-count-first; empty when the entity has no classified posts yet.
- `authorTypes[].sharePct` — `count` as a percentage of `totalClassifiedPosts` (0 when `totalClassifiedPosts` is 0).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18g. Get Content Intent Breakdown (What Kind of Buzz)

**Endpoint:** `GET /api/dashboard/{entityId}/content-intent-breakdown`

**Description:** Ranks the entity's buzz by `content_intent` — `official_promo`, `fan_amplified_promo`, `organic_opinion`, `news_press_coverage`, `trade_box_office_update`, `ticket_merch_marketplace`, etc. Tells the marketing team what the conversation is actually *for*: a high `fan_amplified_promo` share means organic fan advocacy is doing the campaign's work; heavy `trade_box_office_update`/`news_press_coverage` means the buzz is industry chatter rather than audience excitement; a rising `ticket_merch_marketplace` share signals purchase-intent worth capitalizing on with a booking-link push.

Same join pattern and NULL/`irrelevant` exclusion as `author-type-breakdown`.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/content-intent-breakdown
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "totalClassifiedPosts": 298,
  "intents": [
    { "rank": 1, "contentIntent": "organic_opinion", "count": 152, "sharePct": 51.01 },
    { "rank": 2, "contentIntent": "fan_amplified_promo", "count": 61, "sharePct": 20.47 },
    { "rank": 3, "contentIntent": "official_promo", "count": 39, "sharePct": 13.09 },
    { "rank": 4, "contentIntent": "news_press_coverage", "count": 28, "sharePct": 9.40 },
    { "rank": 5, "contentIntent": "trade_box_office_update", "count": 18, "sharePct": 6.04 }
  ]
}
```

**Response fields:**
- `totalClassifiedPosts` — sum of `count` across all returned intents (every non-`irrelevant`, non-null-`content_intent` post/comment linked to the entity).
- `intents` — ranked highest-count-first; empty when the entity has no classified posts yet.
- `intents[].sharePct` — `count` as a percentage of `totalClassifiedPosts` (0 when `totalClassifiedPosts` is 0).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18h. Get Topic Category Breakdown (What Aspects Resonate)

**Endpoint:** `GET /api/dashboard/{entityId}/topic-category-breakdown`

**Description:** Ranks the entity's buzz by `topic_category` — `cast_performance`, `music_songs`, `story_screenplay`, `direction_technical_craft`, `box_office_commercial`, `politics_personal_life_crossover`, `general`, etc. Tells the marketing team which aspect of the movie the audience is actually discussing, so creative/spend can lean into what's resonating (e.g. push music promo when `music_songs` dominates, cast interviews when `cast_performance` does) instead of guessing.

Same join pattern and NULL/`irrelevant` exclusion as `author-type-breakdown`/`content-intent-breakdown`.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/topic-category-breakdown
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "totalClassifiedPosts": 298,
  "topics": [
    { "rank": 1, "topicCategory": "cast_performance", "count": 118, "sharePct": 39.60 },
    { "rank": 2, "topicCategory": "music_songs", "count": 74, "sharePct": 24.83 },
    { "rank": 3, "topicCategory": "story_screenplay", "count": 51, "sharePct": 17.11 },
    { "rank": 4, "topicCategory": "box_office_commercial", "count": 33, "sharePct": 11.07 },
    { "rank": 5, "topicCategory": "direction_technical_craft", "count": 22, "sharePct": 7.38 }
  ]
}
```

**Response fields:**
- `totalClassifiedPosts` — sum of `count` across all returned topics (every non-`irrelevant`, non-null-`topic_category` post/comment linked to the entity).
- `topics` — ranked highest-count-first; empty when the entity has no classified posts yet.
- `topics[].sharePct` — `count` as a percentage of `totalClassifiedPosts` (0 when `totalClassifiedPosts` is 0).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18i. Get AI Summary (Command Center "AI Summary" panel)

**Endpoint:** `GET /api/dashboard/{entityId}/ai-summary`

**Description:** Backs the "AI Summary" panel on the movie Command Center — a short plain-English momentum summary. Exposed as its own endpoint (separate from Today's Highlights, §18j) since the UI loads and refreshes the two panels independently; under the hood both endpoints share a single generation per entity (see below), so they never tell contradictory stories even though they're fetched separately.

This does **not** let the LLM free-associate about the movie. Every number the model can reference is pre-computed server-side from real data — the same sources as their own dedicated endpoints — and handed to it as a JSON "facts" block:
- Current totals (`/stats`) and day-over-day sentiment delta (`/sentiment-delta`, 1-day window)
- Top 3 regions by buzz (`/audience-pulse`)
- Promotional vs. organic split (`/promotional-mix`)
- Top 3 author types, content intents, and topic categories (`/author-type-breakdown`, `/content-intent-breakdown`, `/topic-category-breakdown`)
- Checkpoint impact for any checkpoint in the last 14 days (`/checkpoint-impact`)
- Competitor snapshot (`/competitor-snapshot`), excluding the entity itself

The prompt explicitly instructs the model to use *only* facts present in that JSON and to say nothing about a topic rather than invent a plausible-sounding fact — the same grounding discipline as `conflict-balance`/`narrative-novelty`, just applied to real-time audience data instead of a synopsis. The underlying summary+highlights generation happens at most once every 15 minutes per entity (in-memory cache, shared with §18j — whichever of the two endpoints is called first pays the generation cost); pass `refresh=true` to force regeneration.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Query Parameters:**
- `refresh` (optional, default `false`) - bypass the 15-minute cache and regenerate immediately (also refreshes the cache used by §18j)

**Example Request:**
```
GET /api/dashboard/21/ai-summary
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "summary": "Momentum is building, with buzz up and Tamil Nadu leading regional conversation. Sentiment remains strong, driven largely by organic fan discussion of the cast rather than official promotion.",
  "generatedAt": "2026-08-08T10:15:00Z"
}
```

**Response fields:**
- `summary` — 2-4 sentence plain-English momentum summary.
- `generatedAt` — when the underlying summary was generated (reflects the cached generation time, not necessarily the request time; shared with §18j).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).
- `400 Bad Request` — The LLM response could not be parsed as JSON (transient upstream issue; retry, or pass `refresh=true` on the next call).

---

### 18j. Get Today's Highlights (Command Center "Today's Highlights" panel)

**Endpoint:** `GET /api/dashboard/{entityId}/todays-highlights`

**Description:** Backs the "Today's Highlights" panel on the movie Command Center — a short list of individually-typed bullet points. Shares the exact same grounded facts-block generation and 15-minute cache as `ai-summary` (§18i); calling either endpoint alone still produces both a summary and a highlights list internally, but each endpoint only returns its own slice.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Query Parameters:**
- `refresh` (optional, default `false`) - bypass the 15-minute cache and regenerate immediately (also refreshes the cache used by §18i)

**Example Request:**
```
GET /api/dashboard/21/todays-highlights
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "highlights": [
    { "type": "POSITIVE", "text": "Total mentions rose vs. yesterday, led by a jump in positive-sentiment posts" },
    { "type": "POSITIVE", "text": "Tamil Nadu drives the largest share of regional buzz" },
    { "type": "NEUTRAL", "text": "Only a small share of posts are promotional — most buzz is organic" },
    { "type": "POSITIVE", "text": "Cast performance is the most-discussed topic among fans" }
  ],
  "generatedAt": "2026-08-08T10:15:00Z"
}
```

**Response fields:**
- `highlights` — 3-6 short, individually-typed highlight bullets; can be shorter if the underlying data is sparse (the prompt is instructed to prefer fewer grounded highlights over padding).
- `highlights[].type` — `POSITIVE`, `NEGATIVE`, or `NEUTRAL`.
- `generatedAt` — when the underlying highlights were generated (reflects the cached generation time, not necessarily the request time; shared with §18i).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).
- `400 Bad Request` — The LLM response could not be parsed as JSON (transient upstream issue; retry, or pass `refresh=true` on the next call).

---

### 18k. Get Audience Pulse Aspects ("People Love" / "People Concerned About" chips)

**Endpoint:** `GET /api/dashboard/{entityId}/audience-pulse-aspects`

**Description:** Backs the "People Love" and "People Concerned About" chip lists on the Command Center's Audience Pulse panel. Calls AuraMath's aspect-driver analysis (`GET /api/marketing/aspect-drivers?entityId=`), a proper aspect-based sentiment analysis (ABSA) over the entity's mentions:
- Each candidate aspect is a common-noun lemma (Stanford CoreNLP POS + lemma) with no named-entity tag — this excludes cast/crew names, other referenced films, and hashtags/@handles tagged as nouns, none of which are genuine "aspects of the movie".
- Each aspect is scored using the sentiment of the *single sentence it appears in*, not the sentiment of the whole post — a post like "the music was amazing but the runtime killed it" now correctly scores "music" and "runtime" differently, rather than copying one document-level score onto both.
- An aspect only counts once it's mentioned by both a minimum number of posts *and* a minimum number of distinct authors — the author-diversity floor exists because post volume alone can't distinguish a genuine consensus from one viral thread or bot/campaign account.
- Aspects are ranked by an author-diversity-shrunk impact score (pulls toward neutral when author diversity is low, so a low-diversity outlier can't outrank a broad consensus). `peopleLove` is the top 3 "strengths" (highest average sentiment); `peopleConcerned` is the top 3 "weaknesses" (lowest/most negative).

Two earlier versions of this endpoint had real problems this replaced: an LLM freely extracting aspects from raw post text could latch onto an off-topic tangent mentioned in a single post; a first pass at calling AuraMath directly inherited AuraMath's own bug of copying whole-document sentiment onto every noun with no named-entity filtering (hashtags, @handles, and cast names could rank as top "aspects"). Both are fixed at the source now (see AuraMath's `AspectSentimentAnalyzer`/`AspectDriversPrecomputer`); this endpoint keeps a second, cheap layer of hashtag/handle/cast-name filtering as defense in depth.

Generation is cached per entity (same shape as `ai-summary`/`todays-highlights`, §18i/§18j) and refreshed for every entity every 6 hours; pass `refresh=true` to force regeneration. If AuraMath is unavailable, this endpoint degrades to empty `peopleLove`/`peopleConcerned` arrays rather than erroring.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Query Parameters:**
- `refresh` (optional, default `false`) - bypass the cache and regenerate immediately

**Example Request:**
```
GET /api/dashboard/21/audience-pulse-aspects
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "peopleLove": ["Lead Pair Chemistry", "Music", "Comedy"],
  "peopleConcerned": ["Runtime", "Second Half Pace", "VFX"],
  "generatedAt": "2026-08-08T10:15:00Z"
}
```

**Response fields:**
- `peopleLove` — up to 3 aspects with the highest average sentiment; can be shorter or empty if AuraMath has fewer than 3 qualifying aspects (or is unavailable).
- `peopleConcerned` — up to 3 aspects with the lowest/most negative average sentiment; can be shorter or empty if AuraMath has fewer than 3 qualifying aspects (or is unavailable).
- `generatedAt` — when the underlying aspects were generated (reflects the cached generation time, not necessarily the request time).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18l. Get Recommended Actions (Command Center "Recommended Actions" panel)

**Endpoint:** `GET /api/dashboard/{entityId}/recommended-actions`

**Description:** Backs the "Recommended Actions" panel on the movie Command Center — a prioritized marketing action plan spanning the whole pre/post-release campaign. Built in two strictly separated phases:

1. **Candidate generation (server-computed, no LLM).** Every candidate action's `category`, `confidencePct`, and execution window (`windowStartDaysFromRelease`/`windowEndDaysFromRelease`/`windowLabel`) is computed from real data — `movies_data_collection` genre/language(/budget) comps, this platform's own mention/spreader/hourly-activity data, AuraMath's genre-scoped audience-reach and keyword-scoped brand-evangelist/viral-seed data, or calibrated calendar math (trailer/teaser timing, holiday proximity, etc.). A factor with insufficient real backing data for this entity simply produces no candidate — never a placeholder or guessed number — except a near-zero tracked-mention count, which is itself grounds for a "build visibility" candidate rather than silence. No candidate requires a budget on file: the genre/language comps candidates fall back to comparing across every budget tier when the entity has none (common for the small/independent productions this platform tracks), and the AuraMath-backed candidates (genre-audience-reach, brand-evangelist-outreach, viral-seed-outreach) never needed a budget in the first place. The two outreach candidates report real, AuraMath-classified counts rather than an invented target — e.g. "reach out to these N Tier-1/2 evangelists" — never a made-up percentage of an unknown pool.
2. **LLM select-and-phrase (this endpoint's only model call).** The full candidate list plus the movie's own facts (genre, language, industry, budget, days to release) are handed to the model as read-only context. The model's *only* output is which candidates are genuinely relevant to this specific movie and a short prose reason for each — it never sees a schema field for `category`, `confidencePct`, or either window offset, and never supplies one; those three numbers are merged back onto the selection from the original candidate record, untouched. Every number, statistic, or figure in the model's reason must come from that candidate's own `supportingFacts` — it may restate one in its own words but is instructed never to invent, guess, estimate, or recall one from general knowledge of real movies. For a narrow set of organic/marketing-tactic factors (word-of-mouth, meme trends, micro-video campaigns, influencer promotions) the model may, where genuinely illustrative, name a real movie and describe a marketing tactic it's known for in purely qualitative terms — never with an invented number attached. Any `candidateId` the model returns that doesn't match a candidate it was sent is dropped (logged as a warning), and every returned reason is scanned for a digit sequence not found in that candidate's own supporting facts (also logged as a warning) as a cheap defensive check on top of the prompt-level constraint.

If the LLM call fails, its response can't be parsed, or it selects nothing usable, the endpoint falls back to every server-computed candidate unfiltered, with a generic reason built only from that candidate's own supporting facts — the panel never renders empty just because of an LLM hiccup.

Generation is cached per entity and refreshed for every entity once a day (`refresh=true` forces regeneration) — unlike the 6-hour cadence of `audience-pulse-aspects` (§18k), the underlying facts (genre, budget, historical comps) change rarely, so there's no value in re-running the LLM call more often. What *does* change daily is which phase of the plan is "current": by default this endpoint filters the cached plan down to only the actions whose window currently contains today (computed live against `entity.releaseDate` on every call, not baked into the cached plan); pass `allPhases=true` to see the entire campaign roadmap instead. An entity with no `releaseDate` can't have a "current" window computed, so it always returns the full, unfiltered plan regardless of `allPhases` — and if the window filter would otherwise leave the response empty despite a real generated plan existing (e.g. a movie whose release is further out than any single factor's marketing window reaches), it falls back to the full plan too, so the panel never renders empty just because no window happens to cover today.

**Status tracking (marketing team workflow).** Each action carries a `candidateId` (stable across regenerations — see below) and a `status`: `ACTIVE` (default), `DONE` (the marketing team has acted on it), or `IRRELEVANT` (the team has ruled it out as not applicable to this movie). This endpoint — the "what to do now" panel — only ever returns `ACTIVE` actions; once an action is marked `DONE` or `IRRELEVANT` via [18l-i](#18l-i-update-recommended-action-status) it drops out of this response but is **never deleted**. To see the full history including handled/ruled-out actions, use [18l-ii](#18l-ii-get-all-recommended-actions-history).

A daily regeneration never wipes this history: the fresh candidate list is merged onto whatever is already cached, matched by `candidateId`. A candidate the LLM re-selects keeps whatever status the team already set on it (only its content — title/reason/numbers — is refreshed, since the candidate is grounded in live data that can move day to day); a previously-cached action the LLM doesn't re-select this cycle is carried forward unchanged rather than dropped; a candidate never seen before is added at `ACTIVE`.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Query Parameters:**
- `refresh` (optional, default `false`) - bypass the daily cache and regenerate immediately
- `allPhases` (optional, default `false`) - return the whole cached plan ungrouped/unfiltered (the full campaign roadmap) instead of only the actions whose window currently contains today

**Example Request:**
```
GET /api/dashboard/21/recommended-actions?allPhases=true
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "daysToRelease": -12,
  "actions": [
    {
      "candidateId": "factor-46-teaser-trailer-timing",
      "category": "HIGH_IMPACT",
      "title": "Kick Off Teaser/Trailer Push",
      "reason": "This platform's timing model calibrates a 30-45 day pre-release trailer/teaser window as a +25% impact bonus — now is the window to release it.",
      "confidencePct": 90,
      "relatedFactor": "Teaser/Trailer Timing",
      "windowStartDaysFromRelease": -45,
      "windowEndDaysFromRelease": -30,
      "windowLabel": "4-6 weeks before release",
      "status": "ACTIVE"
    },
    {
      "candidateId": "factor-17-fanbase-mobilization",
      "category": "MEDIUM_IMPACT",
      "title": "Activate Core Fanbase",
      "reason": "12 positive-sentiment accounts have been identified across tracked keywords, 4 of them Tier-1/2 influence accounts — comparable ally mobilization events have correlated with a 1.8x mention-volume lift.",
      "confidencePct": 65,
      "relatedFactor": "Fanbase Mobilization",
      "windowStartDaysFromRelease": -21,
      "windowEndDaysFromRelease": -7,
      "windowLabel": "1-3 weeks before release",
      "status": "ACTIVE"
    }
  ],
  "generatedAt": "2026-08-08T10:15:00Z"
}
```

**Response fields:**
- `daysToRelease` — today's signed day-offset from `entity.releaseDate`, using the same sign convention as the window fields below (negative = before release, positive = after); `null` if the entity has no `releaseDate`.
- `actions` — the (by default, window-filtered) **`ACTIVE`-only** action list, ordered as generated.
- `actions[].candidateId` — stable id of the underlying candidate; unchanged by regeneration, used to target [18l-i](#18l-i-update-recommended-action-status).
- `actions[].category` — `HIGH_IMPACT`, `MEDIUM_IMPACT`, or `LOW_IMPACT`; server-computed in Phase 1, never LLM-authored.
- `actions[].title` — LLM-authored (falls back to the underlying factor's name if the model didn't sharpen it).
- `actions[].reason` — LLM-authored prose grounded only in that action's own supporting facts (or a generic Java-built fallback reason — see Description).
- `actions[].confidencePct` — 0-100; server-computed in Phase 1, never LLM-authored.
- `actions[].relatedFactor` — the underlying marketing factor this action is grounded in (e.g. "Teaser/Trailer Timing", "Fanbase Mobilization").
- `actions[].windowStartDaysFromRelease` / `actions[].windowEndDaysFromRelease` — signed day-offsets from release bounding this action's execution window; server-computed in Phase 1, never LLM-authored.
- `actions[].windowLabel` — human-readable rendering of the window (e.g. `"4-6 weeks before release"`, `"Release week"`).
- `actions[].status` — always `"ACTIVE"` in this endpoint's response (see **Status tracking** above); `generatedAt` — when the underlying plan was generated (reflects the cached generation time, not necessarily the request time).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18l-i. Update Recommended Action Status

**Endpoint:** `PATCH /api/dashboard/{entityId}/recommended-actions/{actionId}/status`

**Description:** Lets the marketing team mark a single recommended action as `DONE` (already acted on) or `IRRELEVANT` (doesn't apply to this movie), or move it back to `ACTIVE`. `actionId` is the action's `candidateId` from [18l](#18l-get-recommended-actions-command-center-recommended-actions-panel)'s response. The status is stored on the cached plan and survives the next daily regeneration (see **Status tracking** above) — it is not reset until explicitly changed again.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `entityId` - ID of the managed entity
- `actionId` - the action's `candidateId` (e.g. `factor-46-teaser-trailer-timing`)

**Request Body:**
```json
{
  "status": "DONE"
}
```

- `status` — required; one of `ACTIVE`, `DONE`, `IRRELEVANT`.

**Example Request:**
```
PATCH /api/dashboard/21/recommended-actions/factor-46-teaser-trailer-timing/status
```

**Response:** The updated action.
```json
{
  "candidateId": "factor-46-teaser-trailer-timing",
  "category": "HIGH_IMPACT",
  "title": "Kick Off Teaser/Trailer Push",
  "reason": "This platform's timing model calibrates a 30-45 day pre-release trailer/teaser window as a +25% impact bonus — now is the window to release it.",
  "confidencePct": 90,
  "relatedFactor": "Teaser/Trailer Timing",
  "windowStartDaysFromRelease": -45,
  "windowEndDaysFromRelease": -30,
  "windowLabel": "4-6 weeks before release",
  "status": "DONE"
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, the entity is owned by another user, no plan has been generated yet for this entity (call [18l](#18l-get-recommended-actions-command-center-recommended-actions-panel) at least once first), or `actionId` doesn't match any action ever generated for this entity.
- `400 Bad Request` — `status` is missing or not one of `ACTIVE`, `DONE`, `IRRELEVANT`.

---

### 18l-ii. Get All Recommended Actions (History)

**Endpoint:** `GET /api/dashboard/{entityId}/recommended-actions/all`

**Description:** Every recommended action ever generated for this entity — past and present — each carrying whatever status the marketing team last set on it, so the team can see at a glance what's already been handled (`DONE`), what's been ruled out (`IRRELEVANT`), and what's still open (`ACTIVE`). Unlike [18l](#18l-get-recommended-actions-command-center-recommended-actions-panel), this is **not** filtered to `ACTIVE` and **not** filtered to today's execution window — it's the full audit view, not the "what to do today" panel. Optionally narrow to a single status with the `status` query parameter.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Query Parameters:**
- `status` (optional) — one of `ACTIVE`, `DONE`, `IRRELEVANT`. Omit to return actions of every status.

**Example Requests:**
```
GET /api/dashboard/21/recommended-actions/all              # everything ever recommended
GET /api/dashboard/21/recommended-actions/all?status=DONE  # only what's been handled
```

**Response:** Same shape as [18l](#18l-get-recommended-actions-command-center-recommended-actions-panel), except `actions` is the full (optionally status-filtered) history rather than the window-filtered, `ACTIVE`-only current plan.
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "daysToRelease": -12,
  "actions": [
    {
      "candidateId": "factor-46-teaser-trailer-timing",
      "category": "HIGH_IMPACT",
      "title": "Kick Off Teaser/Trailer Push",
      "reason": "This platform's timing model calibrates a 30-45 day pre-release trailer/teaser window as a +25% impact bonus — now is the window to release it.",
      "confidencePct": 90,
      "relatedFactor": "Teaser/Trailer Timing",
      "windowStartDaysFromRelease": -45,
      "windowEndDaysFromRelease": -30,
      "windowLabel": "4-6 weeks before release",
      "status": "DONE"
    },
    {
      "candidateId": "factor-61-holiday-proximity",
      "category": "LOW_IMPACT",
      "title": "Avoid Competing Holiday Releases",
      "reason": "Release date is 3 day(s) from Independence Day (2026-08-15); this platform's factor model calibrates holiday-window releases at a +5% to +15% box-office impact.",
      "confidencePct": 55,
      "relatedFactor": "Holiday Release Windows",
      "windowStartDaysFromRelease": 0,
      "windowEndDaysFromRelease": 0,
      "windowLabel": "Release day",
      "status": "IRRELEVANT"
    }
  ],
  "generatedAt": "2026-08-08T10:15:00Z"
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, the entity is owned by another user, or no plan has been generated yet for this entity (call [18l](#18l-get-recommended-actions-command-center-recommended-actions-panel) at least once first).

---

### 18m. Get Movie Health (Command Center "Movie Health" panel)

**Endpoint:** `GET /api/dashboard/{entityId}/movie-health`

**Description:** Backs the "Movie Health" panel — a single percentage distilled from the entity's net sentiment score (the same positive/negative mention-count ratio used by `/stats`, `/sentiment-delta`, `/checkpoint-trend`, etc.: a value of `2.0` means two positive mentions per negative). Health percentage is `min(100, (netSentimentScore / 2.0) * 100)`, so `1.5` (the "good" cutoff) lands at 75% and anything at or above `2.0` (the "excellent" cutoff) saturates at 100%. `healthLabel` is `"Excellent"` above 2.0, `"Good"` above 1.5, otherwise `"Needs Improvement"` — a score of exactly `1.5` or `2.0` does **not** qualify for the higher label, since both cutoffs are exclusive.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/movie-health
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "netSentimentScore": 2.4,
  "healthPercentage": 100.0,
  "healthLabel": "Excellent"
}
```

**Response fields:**
- `netSentimentScore` — positive mentions ÷ negative mentions across the entity's whole history; `0.0` when there are no negative mentions yet (same edge case as every other endpoint that reports this ratio).
- `healthPercentage` — `netSentimentScore` mapped onto a 0–100 scale, floored at 0 and saturating at 100 once the score reaches 2.0.
- `healthLabel` — `"Excellent"` (`netSentimentScore > 2.0`), `"Good"` (`> 1.5`), or `"Needs Improvement"` (everything else, including a movie with no mentions at all).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18n. Get Buzz (Command Center "Buzz" panel)

**Endpoint:** `GET /api/dashboard/{entityId}/buzz`

**Description:** Backs the "Buzz" panel — the change in mention volume versus the prior day. `mentionsToday` counts mentions with `postDate` between today's UTC midnight and now; `mentionsYesterday` counts the full prior UTC day. `mentionsChangePct` is `100.0` when yesterday had zero mentions but today has some (avoids a divide-by-zero while still signaling "buzz appeared from nothing"), and `0.0` when both days are silent.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/buzz
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "mentionsToday": 150,
  "mentionsYesterday": 100,
  "mentionsChange": 50,
  "mentionsChangePct": 50.0
}
```

**Response fields:**
- `mentionsToday` / `mentionsYesterday` — raw mention counts for the current and prior UTC calendar day.
- `mentionsChange` — `mentionsToday - mentionsYesterday` (can be negative).
- `mentionsChangePct` — `mentionsChange` as a percentage of `mentionsYesterday`.

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18o. Get Sentiment (Command Center "Sentiment" panel)

**Endpoint:** `GET /api/dashboard/{entityId}/sentiment`

**Description:** Backs the "Sentiment" panel — the entity's overall average sentiment across its whole mention history (not a time-windowed slice, unlike `/sentiment-delta`). Reuses the same `AVG(sentimentScore)` / positive-ratio query as `/stats`.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/sentiment
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "totalMentions": 412,
  "averageSentimentScore": 1.8,
  "positiveRatio": 0.65
}
```

**Response fields:**
- `totalMentions` — every mention linked to the entity, regardless of sentiment.
- `averageSentimentScore` — mean of the per-mention `sentimentScore` field; `0.0` when the entity has no mentions yet.
- `positiveRatio` — fraction of mentions classified `POSITIVE`; `0.0` when the entity has no mentions yet.

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18p. Get Reach (Command Center "Reach" panel)

**Endpoint:** `GET /api/dashboard/{entityId}/reach`

**Description:** Backs the "Reach" panel — the total number of unique users (distinct mention authors) who have ever posted about the entity, across every platform and the entity's entire history. Unlike `/hourly-activity`'s `countDistinctActiveUsers` (which is scoped to a time window and optional language/industry/state filters), this is a single unfiltered lifetime count.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/reach
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "uniqueUsers": 4321
}
```

**Response fields:**
- `uniqueUsers` — count of distinct (non-null) `author` values across all mentions linked to the entity.

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18p-i. Get Reach (Direct Table Join)

**Endpoint:** `GET /api/dashboard/{entityId}/reach-direct`

**Description:** Same "unique users" metric as [18p](#18p-get-reach-command-center-reach-panel), computed a different way: instead of reading the pre-linked `mentions`/`mention_entities` tables, this joins/UNIONs the four raw ingestion tables (`x_posts`, `instagram_posts`, `youtube_comments`, `reddit_posts`) directly, each on its own `entity` text column (populated by the ingestion pipeline with the movie/entity name) matched case-insensitively against `managed_entities.name`. Posts/comments with `author_type = 'irrelevant'` (an upstream classifier's judgment that the row isn't really about the entity) are excluded; NULL/blank `author_type` (not yet classified) is still counted.

Because the `entity` column isn't populated for every row that the keyword-based `mentions` linking picks up — and conversely can catch rows `mentions` never linked — this can return a different (often larger) count than [18p](#18p-get-reach-command-center-reach-panel) for the same entity. It returns `0` rather than an error for an entity whose name never appears verbatim (case-insensitive) in any raw table's `entity` column.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/reach-direct
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "uniqueUsers": 5786
}
```

**Response fields:**
- `uniqueUsers` — count of distinct (non-null) `author` values across `x_posts`, `instagram_posts`, `youtube_comments`, and `reddit_posts` rows whose `entity` column matches this entity's name and whose `author_type` isn't `'irrelevant'`.

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18q. Get Awareness (Command Center "Awareness" panel)

**Endpoint:** `GET /api/dashboard/{entityId}/awareness`

**Description:** Backs the "Awareness" panel — a High/Medium/Low tier based on total view count compared against the caller's other movies. `totalViews` sums `x_posts.views_count` for every X post linked to the entity; X is the only one of the four ingested platforms (`x_posts`, `youtube_comments`, `reddit_posts`, `instagram_posts`) that carries a real view/impression count, the same limitation the mentions list's `impressions` field has.

The comparison set is every other `MOVIE`-type entity owned by the same user (or, for legacy rows with no owner, every `MOVIE` entity system-wide). Movies are ranked by min-max normalized position — the lowest-viewed movie always lands at position `0.0` and the highest at `1.0` regardless of how many movies are being compared — then bucketed: `>= 2/3` is `"High"`, `>= 1/3` is `"Medium"`, otherwise `"Low"`. With fewer than 2 movies to compare against, ranking is meaningless and the level defaults to `"Medium"`.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Example Request:**
```
GET /api/dashboard/21/awareness
```

**Response:**
```json
{
  "entityId": 21,
  "entityName": "Madhavan",
  "totalViews": 900000,
  "awarenessLevel": "High",
  "comparedMovieCount": 5
}
```

**Response fields:**
- `totalViews` — sum of X-post view counts for the entity; `0` if it has no X posts (or none with views yet).
- `awarenessLevel` — `"High"`, `"Medium"`, or `"Low"`, ranked against `comparedMovieCount` other movies.
- `comparedMovieCount` — size of the comparison set (includes the entity itself).

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18r. Get Top Spreaders' Content

**Endpoint:** `GET /api/dashboard/{entityId}/top-spreaders/content`

**Description:** For the entity's top spreaders — AuraMath's top-50-spreaders identities, cached per (entity, language) in `EntityLanguageSpreaderSnapshot` and refreshed every 2 days by `TopSpreaderLanguageSyncService` — resolves what each spreader has actually posted about this entity: view count, engagement rate, and sentiment per post. A spreader's `globalUserId` is matched directly against `mentions.author` (the same identity equivalence the evangelist-mobilization recommended-action candidate already relies on), so a spreader with no post found under that exact author string still appears in the response (for context, via AuraMath's own `totalViews`) but with an empty `topContent`.

`views` is a per-platform proxy, not a uniform metric — same formulas `GET /{entityId}/awareness` and `findTotalViewsForEntity` use, just returned per post instead of summed: X is a real view count (`x_posts.views_count`); Instagram uses the `views` column, falling back to `like_count + comments_count` when null/0; YouTube shows the post's video's total view count, shared by every comment under that video (`mentions.post_id` for platform `YOUTUBE` points at a comment row, not the video); Reddit shows its subreddit's subscriber count (Reddit exposes no per-post view metric), so every post from the same subreddit shows the same figure. `engagementRate` is `(likes + comments) / views` and is `null` when `views` is `null` or `0`.

Each spreader's candidate pool is capped at their 10 highest-viewed posts **in the database** (a `ROW_NUMBER() OVER (PARTITION BY author ...)` window, ranked by that same view proxy) before `postsPerSpreader` trims further — so a spreader with hundreds of posts about the movie doesn't mean hundreds of rows fetched just to keep a handful. This is also why `postsPerSpreader` tops out at 10.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Query Parameters:**
- `language` (optional) - restricts spreaders to this language's snapshot. Omitted: spreaders are deduped across every language the entity is tracked in.
- `spreaderLimit` (optional, default `10`, 1-50) - max spreaders returned, ranked by AuraMath's `totalViews` descending.
- `postsPerSpreader` (optional, default `5`, 1-10) - max posts returned per spreader, ranked by resolved `views` descending.

**Example Request:**
```
GET /api/dashboard/21/top-spreaders/content?language=Tamil&spreaderLimit=5&postsPerSpreader=3
```

**Response:**
```json
{
  "entityId": 21,
  "language": "Tamil",
  "spreaders": [
    {
      "globalUserId": "Rocking Ashu",
      "profileUrl": "https://x.com/rocking_ashu",
      "totalViews": 10179,
      "topContent": [
        {
          "mentionId": 5031,
          "platform": "X",
          "postId": "184920",
          "content": "This trailer is INSANE. Madhavan is back with a bang!",
          "permalink": "https://x.com/rocking_ashu/status/184920",
          "postDate": "2026-08-10T14:22:00Z",
          "views": 4521,
          "likes": 312,
          "comments": 48,
          "engagementRate": 0.0796,
          "sentiment": "POSITIVE",
          "sentimentScore": 82
        },
        {
          "mentionId": 5090,
          "platform": "INSTAGRAM",
          "postId": "ig-77213",
          "content": "Can't wait for this one 🔥",
          "permalink": "https://instagram.com/p/ig-77213",
          "postDate": "2026-08-09T09:03:00Z",
          "views": 2100,
          "likes": 190,
          "comments": 11,
          "engagementRate": 0.0957,
          "sentiment": "POSITIVE",
          "sentimentScore": 74
        }
      ]
    },
    {
      "globalUserId": "TamilCinemaBuzz",
      "profileUrl": null,
      "totalViews": 5400,
      "topContent": []
    }
  ]
}
```

**Response fields:**
- `spreaders` — ranked by AuraMath's `totalViews` descending; a spreader stays in the list with `topContent: []` when no local post matches their identity.
- `spreaders[].topContent[].views` — `null` when no proxy could be resolved for that post (e.g. the underlying platform row wasn't found).
- `spreaders[].topContent[].engagementRate` — `(likes + comments) / views`, `null` when `views` is `null` or `0`.
- `spreaders[].topContent[].sentiment` / `sentimentScore` — same `Mention.sentiment` (`POSITIVE`/`NEGATIVE`/`NEUTRAL`) and numeric score used throughout the Dashboard APIs.

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).

---

### 18s. Get Top Spreaders' AI Insights

**Endpoint:** `GET /api/dashboard/{entityId}/top-spreaders/insights`

**Description:** Sends the same top-spreader data as [18r](#18r-get-top-spreaders-content) to the LLM to produce collaboration recommendations for the marketing team: a short summary of which spreaders are delivering the most impact, plus concrete per-spreader actions (e.g. "Collaborate with Cinema Vikatan for exclusive interviews").

Follows this platform's "Java computes every number, the LLM only selects and writes prose" split (same convention as [18l](#18l-get-recommended-actions-command-center-recommended-actions-panel)): each spreader with at least one resolved post is ranked by `totalViews` (the only real reach proxy AuraMath provides) and split into thirds — top third `HIGH_IMPACT`, next third `MEDIUM_IMPACT`, rest `LOW_IMPACT` — **before** the LLM ever sees the data. The LLM is given each spreader's impact tier, `totalViews`, average engagement rate, dominant sentiment, and real sample post content, and may only (1) pick up to 5 spreaders worth a collaboration recommendation and (2) write the `action` text for each, grounded in that spreader's real sample content (e.g. music-focused posts suggest a BGM-breakdown collaboration, interview/reaction content suggests an interview or reaction-style one). It never supplies — and its `impact` is never trusted even if it tries — the impact tier; that's always merged back from the server-computed candidate by `spreaderId`. A spreader with no locally-resolved post content is excluded entirely, since there's nothing real to ground a recommendation in.

Generation is persisted per (entity, language, spreaderLimit, postsPerSpreader) so the LLM's latency never blocks the UI: no cached row yet → generates synchronously (nothing else to return); a row younger than 24h → served straight from the database, no LLM call; a row older than 24h → still served immediately as-is, while a regeneration is kicked off in the background to refresh it for the next request (deduped so a burst of concurrent requests for the same key doesn't fire a burst of LLM calls). A failed background regeneration is logged and leaves the previous cached data untouched rather than erroring or blanking the panel. Pass `refresh=true` to bypass all of this and force a synchronous regeneration regardless of the cached row's age.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - ID of the managed entity

**Query Parameters:**
- `language` (optional) - restricts spreaders to this language's snapshot, same as [18r](#18r-get-top-spreaders-content). Omitted: spreaders are deduped across every language the entity is tracked in.
- `spreaderLimit` (optional, default `10`, 1-50) - max spreaders considered, ranked by AuraMath's `totalViews` descending.
- `postsPerSpreader` (optional, default `5`, 1-10) - max posts resolved per spreader before impact-tier ranking and LLM grounding.
- `refresh` (optional, default `false`) - bypass the 24h persisted cache and regenerate immediately, synchronously.

**Example Request:**
```
GET /api/dashboard/21/top-spreaders/insights?language=Tamil&spreaderLimit=10&postsPerSpreader=5
```

**Response:**
```json
{
  "entityId": 21,
  "language": "Tamil",
  "summary": "Cinema Vikatan and Behindwoods TV are delivering the highest impact with strong positive sentiment. Consider collaborations with Filmy Reacts for high engagement and Tamil Talkies for music-driven content.",
  "actions": [
    {
      "spreaderId": "Cinema Vikatan",
      "action": "Collaborate with Cinema Vikatan for exclusive interviews",
      "impact": "HIGH_IMPACT"
    },
    {
      "spreaderId": "Tamil Talkies",
      "action": "Release a BGM breakdown with Tamil Talkies",
      "impact": "HIGH_IMPACT"
    },
    {
      "spreaderId": "Behindwoods TV",
      "action": "Plan a live Q&A with Behindwoods TV",
      "impact": "MEDIUM_IMPACT"
    }
  ],
  "generatedAt": "2026-08-23T10:15:00Z"
}
```

**Response fields:**
- `summary` — 2-3 sentence plain-English overview of which spreaders are delivering the most impact and what kind of collaboration each seems suited for; LLM-authored, grounded only in the spreader data sent to it.
- `actions[].spreaderId` — matches a `spreaders[].globalUserId` from [18r](#18r-get-top-spreaders-content).
- `actions[].action` — LLM-authored, specific collaboration recommendation grounded in that spreader's real sample post content.
- `actions[].impact` — `HIGH_IMPACT` / `MEDIUM_IMPACT` / `LOW_IMPACT`, the same server-computed reach tier used by [18l](#18l-get-recommended-actions-command-center-recommended-actions-panel)'s `category`; never LLM-authored.
- `generatedAt` — when this response was generated (reflects the cached generation time, not necessarily the request time).
- An entity with no spreaders carrying resolved post content returns `summary: ""` and `actions: []` rather than erroring — there's nothing grounded to generate insights from yet.

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No such entity, or the entity is owned by another user (indistinguishable by design).
- `400 Bad Request` — The LLM response could not be parsed as JSON, or was missing `summary`/`actions` (transient upstream issue; retry, or pass `refresh=true` on the next call).

---

## Interaction APIs

### 19. Generate Reply

**Endpoint:** `GET /api/interact/generate-reply/{post_id}`

**Description:** Generate an AI-powered reply to a mention by looking up the mention's content, sentiment, and managed entity name. (Mock LLM)

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `post_id` — Mention ID (e.g., 1)

**Example Request:**
```
GET /api/interact/generate-reply/9123
```

**Response:**
```json
{
  "generatedReply": "This is a mock LLM-generated reply. In production, this would be generated by an actual LLM based on the prompt: Generate a professional reply to the following negative mention: This movie was terrible! Waste of money."
}
```

**Status Codes:**
- `200 OK`
- `404 Not Found` — No mention with the given id, or the mention's entity is owned by another user (indistinguishable by design, so existence is never leaked).

---

### 20. Post Response

**Endpoint:** `POST /api/interact/respond`

**Description:** Post a reply to a social media platform (Mock implementation)

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Request Body:**
```json
{
  "platform": "X",
  "postIdToReplyTo": "tweet_12345",
  "replyText": "Thank you for your feedback! We appreciate your input and are always working to improve."
}
```

**Response:**
```json
"Reply posted successfully (mock)"
```

**Status Code:** `200 OK`

**Note:** In production, this would actually post to the specified platform. The mock implementation logs to console.

---

## Reply Templates APIs

Reply templates are reusable, user-owned canned responses that the UI can offer when replying to mentions. Each template belongs to the authenticated user; a user can only see and modify their own templates.

A `ReplyTemplate` has:

- `id` — server-assigned identifier.
- `userId` — the owning user. Always derived from the authenticated principal; never accepted in the request body.
- `name` — short label for the template (required).
- `body` — the reply text (required).
- `tone` — optional free-text tone hint (e.g. `apologetic`, `friendly`, `formal`).
- `useCount` — number of times the template has been used (starts at `0`, incremented by the `use` endpoint).
- `createdAt` — server timestamp when the template was created.

All routes are JWT-protected — pass `Authorization: Bearer {jwt_token}`. "Not found" and ownership violations return `400 Bad Request` (via the global error handler) with a `message` describing the failure.

### 20a. Create Reply Template

**Endpoint:** `POST /api/templates`

**Description:** Create a reply template owned by the authenticated user. `useCount` starts at `0` and `createdAt` is set to the current server time.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "name": "Apology - delayed response",
  "body": "Thanks for flagging this — we're sorry for the delay and are looking into it now.",
  "tone": "apologetic"
}
```

**Validation:**
- `name` — required, must not be blank.
- `body` — required, must not be blank.
- `tone` — optional.

**Response:**
```json
{
  "id": 5,
  "name": "Apology - delayed response",
  "body": "Thanks for flagging this — we're sorry for the delay and are looking into it now.",
  "tone": "apologetic",
  "useCount": 0,
  "createdAt": "2026-05-31T09:15:00Z"
}
```

**Status Codes:**
- `200 OK` — Template created.
- `400 Bad Request` — Validation failure (blank `name` or `body`).

---

### 20b. List Reply Templates

**Endpoint:** `GET /api/templates`

**Description:** List all reply templates owned by the authenticated user, ordered by `createdAt` descending (most recent first).

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Response:**
```json
[
  {
    "id": 6,
    "name": "Thank you",
    "body": "Thank you so much for the kind words — it means a lot to the whole team!",
    "tone": "friendly",
    "useCount": 12,
    "createdAt": "2026-05-31T10:00:00Z"
  },
  {
    "id": 5,
    "name": "Apology - delayed response",
    "body": "Thanks for flagging this — we're sorry for the delay and are looking into it now.",
    "tone": "apologetic",
    "useCount": 0,
    "createdAt": "2026-05-31T09:15:00Z"
  }
]
```

**Status Code:** `200 OK`

---

### 20c. Update Reply Template

**Endpoint:** `PUT /api/templates/{id}`

**Description:** Replace the `name`, `body`, and `tone` of a template owned by the authenticated user. `useCount` and `createdAt` are preserved.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `id` — Reply template ID

**Request Body:** Same shape and validation as [Create Reply Template](#20a-create-reply-template).
```json
{
  "name": "Apology - delayed response (v2)",
  "body": "Apologies for the delay — we've escalated this and will follow up shortly.",
  "tone": "formal"
}
```

**Response:** The updated template (same shape as a list element).

**Status Codes:**
- `200 OK` — Template updated.
- `400 Bad Request` — Validation failure, or no template with that id owned by the calling user.

---

### 20d. Delete Reply Template

**Endpoint:** `DELETE /api/templates/{id}`

**Description:** Delete a reply template owned by the authenticated user.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `id` — Reply template ID

**Example:**
```
DELETE /api/templates/5
```

**Status Codes:**
- `204 No Content` — Template deleted.
- `400 Bad Request` — No template with that id owned by the calling user.

---

### 20e. Use Reply Template

**Endpoint:** `POST /api/templates/{id}/use`

**Description:** Mark a template as used: increments its `useCount` and returns the template `body` so the UI can drop it straight into a reply box.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `id` — Reply template ID

**Example:**
```
POST /api/templates/6/use
```

**Response:**
```json
{
  "body": "Thank you so much for the kind words — it means a lot to the whole team!"
}
```

**Status Codes:**
- `200 OK` — `useCount` incremented; body returned.
- `400 Bad Request` — No template with that id owned by the calling user.

---

## Crisis Management APIs

> **Tier-gated (GOLD).** Crisis Management (`/api/crisis/**`) requires at least the **GOLD** tier; a
> lower-tier caller still gets `200 OK` with an `EntitledResponse` whose `entitled=false` and a masked
> `preview` of the generated plan. Admins are always entitled. See
> [Premium Feature Tier Gating](#premium-feature-tier-gating).

### 21. Generate Crisis Plan

**Endpoint:** `POST /api/crisis/generate-plan`

**Description:** Generate a detailed crisis management plan (Mock LLM) for an entity the caller owns.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Request Body:**
```json
{
  "entityId": 1,
  "crisisDescription": "Negative reviews are flooding social media after controversial scene in the movie"
}
```

**Response:** Wrapped in an [`EntitledResponse`](#premium-feature-tier-gating) envelope; the generated plan is the `data` field.
```json
{
  "entitled": true,
  "requiredTier": "GOLD",
  "data": {
    "generatedPlan": "Mock Crisis Management Plan:\n\n1. Immediate Response: Issue a public statement acknowledging the situation.\n2. Assessment: Gather all facts and assess the severity of the crisis.\n3. Communication Strategy: Develop key messages for different stakeholders.\n4. Action Plan: Implement corrective measures and monitor progress.\n5. Follow-up: Continue monitoring sentiment and adjust strategy as needed.\n\nThis is a mock plan. In production, this would be generated by an actual LLM based on: Generate a detailed crisis management plan for The Quantum Paradox (MOVIE) regarding the following crisis: Negative reviews are flooding social media after controversial scene in the movie"
  },
  "preview": null
}
```

A caller below **GOLD** instead gets `entitled: false`, `data: null`, and a masked `preview` (the plan text starred out), e.g.:
```json
{ "entitled": false, "requiredTier": "GOLD", "data": null, "preview": { "generatedPlan": "★★★★★" } }
```

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` - The `entityId` does not exist, or the entity is owned by another user (indistinguishable by design).

---

## Playbook Library APIs

A **playbook** is a persisted `CrisisPlan` promoted into a reusable, entity-scoped library. Plans are seeded by the AI draft flow ([Escalate to Crisis](#25-escalate-to-crisis) persists a `CrisisPlan` row), then curated by the team: renamed, tagged, favorited, and re-used as the starting point for future plans.

A `CrisisPlan` exposed through these endpoints has:

- `id` — server-assigned identifier.
- `entityId` — the managed entity the plan belongs to.
- `mentionId` — the mention the plan was originally escalated from (carried over on clone).
- `title` — short, editable label for the playbook (set/edited via update; `null` until named).
- `planText` — the plan body, AI-drafted on escalation and freely editable afterwards.
- `tags` — list of free-text tags for filtering the library (e.g. `launch`, `review-bomb`, `legal`).
- `isFavorite` — whether the playbook is pinned as a favorite.
- `createdBy` — the user who created (or cloned) the plan.
- `createdAt` — server timestamp when the plan was created.

All routes are JWT-protected — pass `Authorization: Bearer {jwt_token}`. The library is shared across the team at the entity level (not restricted to the plan's author). "Not found" returns `400 Bad Request` via the global error handler with a `message`.

### 21a. List Playbooks

**Endpoint:** `GET /api/playbooks?entityId=&tag=&favorite=`

**Description:** List playbooks, ordered by `createdAt` descending (most recent first). All query parameters are optional and combine as an AND filter.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `entityId` — optional; restrict to one managed entity. When omitted, all playbooks are scanned.
- `tag` — optional; keep only playbooks carrying this exact tag.
- `favorite` — optional `true`/`false`; keep only playbooks matching the favorite flag.

**Example Request:**
```
GET /api/playbooks?entityId=1&tag=review-bomb&favorite=true
```

**Response:**
```json
[
  {
    "id": 22,
    "entityId": 1,
    "mentionId": 9123,
    "title": "Review-bomb response (playbook)",
    "planText": "Immediate Response (0 to 4 Hours): ...",
    "tags": ["review-bomb", "launch"],
    "isFavorite": true,
    "createdBy": 4,
    "createdAt": "2026-05-21T11:00:00Z"
  }
]
```

**Status Code:** `200 OK`

---

### 21b. Update Playbook

**Endpoint:** `PUT /api/playbooks/{id}`

**Description:** Edit a playbook after the AI draft. Each field is optional — fields omitted (or `null`) are left unchanged — so the UI can rename, retag, (un)favorite, or rewrite the plan text independently.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `id` — Playbook (CrisisPlan) ID

**Request Body:**
```json
{
  "title": "Review-bomb response (playbook)",
  "planText": "Immediate Response (0 to 4 Hours): ...",
  "tags": ["review-bomb", "launch"],
  "isFavorite": true
}
```

**Validation:**
- `title` — optional; replaces the title when present.
- `planText` — optional; when present must not be blank.
- `tags` — optional; when present, replaces the full tag list.
- `isFavorite` — optional; when present, sets the favorite flag.

**Response:** The updated playbook (same shape as a list element).

**Status Codes:**
- `200 OK` — Playbook updated.
- `400 Bad Request` — Blank `planText`, or no playbook with that id.

---

### 21c. Clone Playbook

**Endpoint:** `POST /api/playbooks/{id}/clone`

**Description:** Start a new playbook from a past one. The clone copies the source's `entityId`, `mentionId`, `planText`, and `tags`; resets `isFavorite` to `false`; sets `createdBy` to the calling user and `createdAt` to the current server time. The new plan is independent of the source — editing it does not affect the original.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `id` — Source playbook (CrisisPlan) ID

**Request Body:** Optional. When `title` is provided it names the clone; otherwise the clone is named `"Copy of {source title}"`.
```json
{
  "title": "Q3 launch crisis plan"
}
```

**Response:** The newly created playbook (same shape as a list element), with `201 Created`.

**Status Codes:**
- `201 Created` — Clone created.
- `400 Bad Request` — No playbook with that id.

---

## Mention Action APIs

Per-mention actions that wrap the LLM and social-media services into auditable, persisted operations. Most endpoints are mounted under `/api/mentions/{mentionId}/actions`; **Report abuse** (`/api/mentions/{mentionId}/report-abuse`) and **Delete mention** (`DELETE /api/mentions/{mentionId}`) are sibling routes directly under `/api/mentions/{mentionId}`. All are JWT-protected — pass `Authorization: Bearer {jwt_token}`.

- **List actions** returns every `ReplyDraft`, `CrisisPlan`, and mobilize call ever recorded for the mention, with the actor's username on every row, sorted newest first. Used by the UI to show "you already drafted a reply 2h ago" so users don't double-act.
- **Draft reply** generates a reply via `LLMService.generateReply` (entity name + mention content + sentiment) and persists a `ReplyDraft` row (`status=DRAFT`). Outer quotes from the LLM output are stripped to match the existing `/api/interact/generate-reply` behavior.
- **Post reply** loads a previously created draft, calls `SocialMediaService.postReply(platform, postId, text)` against the mention's source platform and post id, and flips the draft to `status=POSTED` with `postedAt` set to the server time.
- **Escalate to crisis** generates a crisis-management plan via `LLMService.generateCrisisPlan` using the mention's content as the crisis description, and persists a `CrisisPlan` row attributed to the calling user.
- **Mobilize allies** pulls the entity's keywords, fans out parallel calls to `GET /v1/top-spreaders/{keyword}` (via the existing AuraMath WebClient and `TopSpreaderLookupService`), filters the union of spreaders down to authors whose mention sentiment for this entity is predominantly `POSITIVE`, and returns the top 10 with a per-ally suggested DM template generated via `LLMService`. Responses are cached in-process per `(entityId, mentionId)` for 5 minutes. Every call (including cache hits) persists a `MobilizeAction` row attributed to the calling user so the action log can show prior mobilize attempts.
- **Report abuse** files an abuse complaint against the mention and persists an `AbuseReport` row attributed to the calling user with `status=SUBMITTED`. The report is then forwarded to a per-platform moderation strategy (`AbuseReportDispatcher`) chosen from the mention's platform (X, Reddit, YouTube, Instagram); the strategy returns an external ticket reference that is stamped onto `externalRef`. Platform strategies are stubs today (they log and return a fake ticket id) — the real platform APIs (Reddit `/api/report`, X media moderation endpoints, etc.) are plugged into those strategies later.

Every action response except **Report abuse** includes a `mention` object shaped like `MentionResponse` so the UI can render the action result without a second fetch; Report abuse returns the persisted `AbuseReport` directly.

> **Ownership:** Every per-mention route is scoped to the owner of the entity the mention belongs to. If the mention does not exist, the route returns `404 Not Found`; if it exists but its entity is owned by another user, it also returns `404 Not Found` (the two are indistinguishable so existence is never leaked). This applies to list actions, draft/post reply, escalate, mobilize, report abuse, and delete.

### 22. List Mention Actions

**Endpoint:** `GET /api/mentions/{mentionId}/actions`

**Description:** Return every `ReplyDraft`, `CrisisPlan`, and `MobilizeAction` row recorded for the mention, merged into a single timeline sorted by `createdAt` descending (newest first). Each row carries the acting user's username so the UI can show "you already drafted a reply 2h ago" and prevent users from double-acting.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `mentionId` — Mention ID to fetch the action log for

**Query Parameters:**
- `ownerId` — (admin only) require the mention's entity to belong to this user; a non-admin who supplies it gets `403 Forbidden`. Optional.

**Example Request:**
```
GET /api/mentions/9123/actions
```

**Response:**
```json
[
  {
    "type": "CRISIS_PLAN",
    "id": 22,
    "actor": "ops_user",
    "createdAt": "2026-05-21T11:00:00Z",
    "draftStatus": null,
    "text": null,
    "postedAt": null,
    "planText": "Immediate Response (0 to 4 Hours): ...",
    "allyCount": null
  },
  {
    "type": "MOBILIZE",
    "id": 33,
    "actor": "ops_user",
    "createdAt": "2026-05-21T10:00:00Z",
    "draftStatus": null,
    "text": null,
    "postedAt": null,
    "planText": null,
    "allyCount": 4
  },
  {
    "type": "REPLY_DRAFT",
    "id": 11,
    "actor": "second_user",
    "createdAt": "2026-05-21T09:00:00Z",
    "draftStatus": "POSTED",
    "text": "We hear you, and we're sorry the film didn't land for you...",
    "postedAt": "2026-05-21T09:05:00Z",
    "planText": null,
    "allyCount": null
  }
]
```

**Response fields:**
- `type` — one of `REPLY_DRAFT`, `CRISIS_PLAN`, `MOBILIZE`.
- `id` — primary key of the underlying row (e.g. the `ReplyDraft.id`).
- `actor` — username of the user who triggered the action (resolved from `User.id`). May be `null` if the user record was hard-deleted.
- `createdAt` — when the action was recorded (sort key).
- `draftStatus`, `text`, `postedAt` — set only for `REPLY_DRAFT` rows. `draftStatus` is `DRAFT` or `POSTED`; `postedAt` is `null` for unposted drafts.
- `planText` — set only for `CRISIS_PLAN` rows; the full generated plan body.
- `allyCount` — set only for `MOBILIZE` rows; number of allies returned by that call (`0` if no keywords or no positive supporters matched).

**Notes:**
- Each `ReplyDraft` is a single row even after it's been posted — the draft creation and the subsequent post are not separate timeline entries. Use `postedAt`/`draftStatus` to distinguish.
- Each call to `POST /mobilize-allies` produces a row, including cache hits, so the timeline reflects every user-triggered mobilize attempt.
- The endpoint is not paginated — typical mentions accumulate at most a handful of actions.

**Status Codes:**
- `200 OK` — Returns the (possibly empty) list of actions.
- `404 Not Found` — No mention with the given id.

---

### 23. Draft Reply

**Endpoint:** `POST /api/mentions/{mentionId}/actions/draft-reply`

**Description:** Generate an AI-drafted reply for the mention and persist it as a `ReplyDraft` in `DRAFT` status. The calling user is recorded as the draft's owner.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `mentionId` — Mention ID to draft a reply for

**Example Request:**
```
POST /api/mentions/9123/actions/draft-reply
```

**Response:**
```json
{
  "mention": {
    "id": 9123,
    "managedEntityId": 1,
    "platform": "X",
    "postId": "tweet_12345",
    "content": "This movie was terrible! Waste of money.",
    "author": "alice",
    "postDate": "2026-05-21T11:50:00Z",
    "sentiment": "NEGATIVE",
    "permalink": "https://x.com/alice/status/9123",
    "sentimentScore": 12,
    "impressions": "4821"
  },
  "draftId": 501,
  "generatedText": "We hear you, and we're sorry the film didn't land for you. If you have a moment, we'd love to know which scene fell flat — your feedback genuinely shapes what we make next."
}
```

**Status Codes:**
- `200 OK` — Draft created.
- `404 Not Found` — No mention with the given id.

---

### 24. Post Reply

**Endpoint:** `POST /api/mentions/{mentionId}/actions/post-reply`

**Description:** Publish a previously drafted reply to the mention's source platform. Calls `SocialMediaService.postReply` with the mention's `platform` and `postId` and the draft's stored text. On success the draft is updated to `status=POSTED` and `postedAt` is set to the current server time.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `mentionId` — Mention ID the draft was created against

**Request Body:**
```json
{
  "draft_id": 501
}
```

**Validation:**
- `draft_id` is required.
- The draft must belong to the same `mentionId` provided in the path — drafts from a different mention are rejected with `404`.

**Response:**
```json
{
  "mention": {
    "id": 9123,
    "managedEntityId": 1,
    "platform": "X",
    "postId": "tweet_12345",
    "content": "This movie was terrible! Waste of money.",
    "author": "alice",
    "postDate": "2026-05-21T11:50:00Z",
    "sentiment": "NEGATIVE",
    "permalink": "https://x.com/alice/status/9123",
    "sentimentScore": 12,
    "impressions": "4821"
  },
  "draftId": 501,
  "text": "We hear you, and we're sorry the film didn't land for you...",
  "postedAt": "2026-05-21T12:14:08Z",
  "result": "Reply posted successfully (mock)"
}
```

**Status Codes:**
- `200 OK` — Reply posted and draft marked `POSTED`.
- `400 Bad Request` — `draft_id` missing.
- `404 Not Found` — Mention not found, draft not found, or draft belongs to a different mention.

**Note:** The default `MockSocialMediaService` only logs to console and returns the literal string `"Reply posted successfully (mock)"`. In production, swap in a real `SocialMediaService` implementation per platform.

---

### 25. Escalate to Crisis

**Endpoint:** `POST /api/mentions/{mentionId}/actions/escalate-to-crisis`

**Description:** Generate a crisis-management plan for the mention's managed entity and persist it as a `CrisisPlan` row attributed to the calling user. The mention's `content` is passed as the crisis description.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `mentionId` — Mention ID to escalate

**Example Request:**
```
POST /api/mentions/9123/actions/escalate-to-crisis
```

**Response:**
```json
{
  "mention": {
    "id": 9123,
    "managedEntityId": 1,
    "platform": "X",
    "postId": "tweet_12345",
    "content": "This movie was terrible! Waste of money.",
    "author": "alice",
    "postDate": "2026-05-21T11:50:00Z",
    "sentiment": "NEGATIVE",
    "permalink": "https://x.com/alice/status/9123",
    "sentimentScore": 12,
    "impressions": "4821"
  },
  "planId": 88,
  "plan": "Immediate Response (0 to 4 Hours): ...\nAssessment & Intelligence: ...\nStakeholder Communication Strategy: ...\nAction & Remediation: ...\nMonitoring & Post-Mortem: ..."
}
```

**Status Codes:**
- `200 OK` — Plan generated and persisted.
- `404 Not Found` — No mention with the given id.

---

### 26. Mobilize Allies

**Endpoint:** `POST /api/mentions/{mentionId}/actions/mobilize-allies`

**Description:** Identify a shortlist of known supporters (top spreaders who are predominantly positive about this entity) and produce a per-ally suggested DM template ops can use to amplify the mention.

The endpoint:
1. Loads the mention's managed entity and its keywords.
2. Fans out parallel calls to `GET /v1/top-spreaders/{keyword}` for every keyword (via the existing AuraMath `WebClient` and `TopSpreaderLookupService`). Per-keyword spreader profiles are cached for 10 minutes by the lookup service.
3. Dedups the union of spreaders by `globalUserId`.
4. Filters the candidates to authors whose mention sentiment for this entity in `MentionRepository` is **predominantly POSITIVE** — i.e. positive count > 0, strictly greater than negative count, and ≥ neutral count.
5. Ranks survivors by positive-mention count (desc), then influence tier (`TIER_1` first), then `globalUserId` (lexicographic), and keeps the top 10.
6. For each surviving ally, calls `LLMService.generateReply` with the `llm.prompt.generate.ally.dm` template (`[Managed Entity]`, `[Ally Handle]`, `[Ally Platform]`, `[Ally Tier]`, `[Mention Content]`) and strips outer quotes from the output.
7. Caches the response in-process per `(entityId, mentionId)` key for **5 minutes**.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `mentionId` — Mention ID to mobilize allies for

**Example Request:**
```
POST /api/mentions/9123/actions/mobilize-allies
```

**Response:**
```json
{
  "mention": {
    "id": 9123,
    "managedEntityId": 1,
    "platform": "X",
    "postId": "tweet_12345",
    "content": "Just rewatched the trailer — this looks incredible.",
    "author": "fan42",
    "postDate": "2026-05-21T11:50:00Z",
    "sentiment": "POSITIVE",
    "permalink": "https://x.com/fan42/status/9123",
    "sentimentScore": 88,
    "impressions": "12904"
  },
  "allies": [
    {
      "globalUserId": "alice",
      "primaryPlatform": "TWITTER",
      "influenceTier": "TIER_1",
      "suggestedDm": "Hey alice — you've been one of the strongest voices for The Quantum Paradox lately. Would love your take on the new trailer if you have a second."
    },
    {
      "globalUserId": "bob",
      "primaryPlatform": "INSTAGRAM",
      "influenceTier": "TIER_2",
      "suggestedDm": "Hi bob, the trailer just dropped and reminded me of your thread last week — any chance you'd share your reaction?"
    }
  ]
}
```

**Response fields:**
- `mention` — full `MentionResponse` so the UI can render the source mention alongside the recommendations without a second fetch.
- `allies` — up to 10 entries. Empty when the entity has no keywords, no top-spreader matches, or no candidate is predominantly positive.
  - `globalUserId` — author identifier as returned by the upstream top-spreaders endpoint (matched against `Mention.author` for the sentiment filter).
  - `primaryPlatform` — best platform to DM on, sourced from the spreader payload (may be `null` if upstream omits it).
  - `influenceTier` — spreader tier from the upstream payload, e.g. `TIER_1`..`TIER_4` (may be `null`).
  - `suggestedDm` — LLM-generated DM template, outer quotes stripped.

**Caching:**
- The full response is cached per `(entityId, mentionId)` for 5 minutes. Within that window the upstream and the LLM are not re-invoked.
- Underlying per-keyword spreader profiles are cached by `TopSpreaderLookupService` for 10 minutes, so cross-mention calls within the same entity also benefit on cold cache.

**Status Codes:**
- `200 OK` — Allies returned (possibly empty).
- `404 Not Found` — No mention with the given id.

---

### 26a. Report Abuse

**Endpoint:** `POST /api/mentions/{mentionId}/report-abuse`

**Description:** File an abuse complaint against a mention and persist it as an `AbuseReport` row attributed to the calling user. The report is created with `status=SUBMITTED` and `submittedAt` set to the current server time. It is then forwarded to the platform-specific moderation strategy for the mention's platform, which returns an external ticket reference stored in `externalRef`. (Strategies are stubs today and return a fake ticket id; if no strategy is registered for the platform, `externalRef` stays `null`.) Returns the persisted `AbuseReport`.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `mentionId` — Mention ID being reported

**Request Body:**
```json
{
  "category": "HARASSMENT",
  "notes": "Repeated targeted abuse against the entity's staff."
}
```

**Request fields:**
- `category` *(required)* — abuse category. One of `HARASSMENT`, `MISINFORMATION`, `IMPERSONATION`, `OTHER`.
- `notes` *(optional)* — free-text context for the report.

**Response:**
```json
{
  "id": 4242,
  "mentionId": 9123,
  "userId": 55,
  "category": "HARASSMENT",
  "notes": "Repeated targeted abuse against the entity's staff.",
  "status": "SUBMITTED",
  "externalRef": "x-mod-4242",
  "submittedAt": "2026-05-31T12:00:00Z"
}
```

**Response fields:**
- `id` — server-assigned identifier for the report.
- `mentionId` — the reported mention.
- `userId` — the reporting user, derived from the authenticated principal (never accepted in the body).
- `category` — the submitted abuse category.
- `notes` — the submitted notes (`null` if omitted).
- `status` — always `SUBMITTED` on creation.
- `externalRef` — external ticket reference returned by the platform moderation strategy on submission; `null` only when no strategy is registered for the mention's platform.
- `submittedAt` — server timestamp when the report was filed.

**Status Codes:**
- `200 OK` — Report filed and persisted.
- `400 Bad Request` — `category` is missing or not a valid value.
- `404 Not Found` — No mention with the given id.

---

### 26b. Get Abuse Reports for a Mention

**Endpoint:** `GET /api/mentions/{mentionId}/abuse-reports`

**Description:** List every abuse report filed against a single mention, newest first — the mention-scoped counterpart to [Report Abuse](#26a-report-abuse). Every report in the list carries the same nested mention summary (loaded once, not per-report).

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `mentionId` — Mention ID to list reports for

**Example Request:**
```
GET /api/mentions/9123/abuse-reports
```

**Response:**
```json
[
  {
    "id": 4242,
    "mentionId": 9123,
    "userId": 55,
    "category": "HARASSMENT",
    "notes": "Repeated targeted abuse against the entity's staff.",
    "status": "SUBMITTED",
    "externalRef": "x-mod-4242",
    "submittedAt": "2026-05-31T12:00:00Z",
    "resolvedAt": null,
    "mention": {
      "id": 9123,
      "author": "user_handle",
      "text": "Original post text...",
      "platform": "TWITTER",
      "permalink": "https://twitter.com/user_handle/status/9123",
      "sourceUrl": null
    }
  }
]
```

**Status Codes:**
- `200 OK` — Returns the (possibly empty) list of reports filed against the mention.
- `404 Not Found` — No mention with the given id.

---

### 26c. Delete Mention

**Endpoint:** `DELETE /api/mentions/{mentionId}`

**Description:** Permanently remove a mention from the `mentions` table. Intended for purging **false-positive mentions** — posts that were attributed to an entity but should not have been (e.g. a post that slipped past sentiment scoring with a non-zero sentiment value despite being irrelevant). The delete also cleans up every record that hangs off the mention in the same transaction — abuse reports, reply drafts, mobilize actions, and crisis plans filed against it — so no orphaned rows remain. This is a hard delete and cannot be undone.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `mentionId` — ID of the mention to delete (the numeric `id` from any mentions listing, e.g. [Get Filtered Mentions](#16-get-filtered-mentions)). This is the internal mention id, not the platform `post_id`.

**Example:**
```
DELETE /api/mentions/9123
```

**Response:** Empty body.

**Status Codes:**
- `204 No Content` — Mention (and its dependent records) deleted.
- `404 Not Found` — No mention with the given id, or the mention's entity is owned by another user (indistinguishable by design, so existence is never leaked).

**Frontend integration notes:**
- The mention's `id` is the same value used by the other per-mention routes (`/api/mentions/{mentionId}/actions`, `report-abuse`); reuse it directly — no separate lookup is needed.
- On a `204`, remove the mention from the local list/cache. Because the row is gone server-side, it will not reappear in subsequent mention queries or be re-counted in sentiment aggregates.
- Treat a `404` as already-deleted (e.g. a double click or a stale list) and reconcile the UI by dropping the row rather than surfacing a hard error.
- Guard this behind a confirmation dialog in the UI — it is irreversible and removes the mention for all users of the workspace, not just the caller.

Example call:
```javascript
async function deleteMention(mentionId, token) {
  const res = await fetch(`/api/mentions/${mentionId}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (res.status === 204 || res.status === 404) {
    return; // gone server-side either way — drop it from the UI
  }
  throw new Error(`Failed to delete mention ${mentionId}: ${res.status}`);
}
```

---

### 26d. List Abuse Reports

**Endpoint:** `GET /api/abuse-reports`

**Description:** List every abuse report the calling user has filed, newest first. Unlike [Get Abuse Reports for a Mention](#26b-get-abuse-reports-for-a-mention), which scopes to one mention, this is a user-wide view across all of the caller's reports — intended for a "my abuse reports" audit screen. Each report includes a nested summary of the reported mention (`null` if that mention has since been [deleted](#26c-delete-mention)).

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `status` *(optional)* — narrow to a single lifecycle stage: `SUBMITTED`, `UPHELD`, or `REJECTED`. Omit to return all statuses.

**Example Request:**
```
GET /api/abuse-reports?status=SUBMITTED
```

**Response:**
```json
[
  {
    "id": 4242,
    "mentionId": 9123,
    "userId": 55,
    "category": "HARASSMENT",
    "notes": "Repeated targeted abuse against the entity's staff.",
    "status": "SUBMITTED",
    "externalRef": "x-mod-4242",
    "submittedAt": "2026-05-31T12:00:00Z",
    "resolvedAt": null,
    "mention": {
      "id": 9123,
      "author": "user_handle",
      "text": "Original post text...",
      "platform": "TWITTER",
      "permalink": "https://twitter.com/user_handle/status/9123",
      "sourceUrl": null
    }
  }
]
```

**Response fields:**
- Same fields as [Report Abuse](#26a-report-abuse)'s response ([status](#26a-report-abuse) values: `SUBMITTED`, `UPHELD`, `REJECTED`), plus:
- `resolvedAt` — set once the moderation backend reaches a terminal status (`UPHELD`/`REJECTED`); `null` while `SUBMITTED`.
- `mention` — summary of the reported mention (`id`, `author`, `text`, `platform`, `permalink`); `null` if the mention has since been deleted.

**Status Code:** `200 OK`

---

## Analytics APIs

### 27. Get Box Office Prediction

**Endpoint:** `GET /api/analytics/{movieId}`

**Description:** Get predicted box office revenue for a movie (Mock analytics). The `movieId` must reference a movie entity owned by the caller.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `movieId` - Movie entity ID (e.g., 1)

**Example Request:**
```
GET /api/analytics/11
```

**Response:**
```json
{
  "movieId": 11,
  "predictedBoxOffice": {
    "prediction_metadata": {
      "identified_period": "January Week 3",
      "analysis_logic": "The release date is within the final week of January, which has evolved into a secondary lucrative slot. The Positivity Ratio and Sentiment Score are adjusted downwards due to their lower values compared to historical benchmarks, leading to projected figures at the 'Lower' end of the historical ranges."
    },
    "financial_projections": {
      "opening_day_collection": {
        "estimated_range": "?6 cr ? ?8 cr",
        "confidence_level": "70%"
      },
      "average_weekend_gross_cumulative": "?95 cr ? ?115 cr",
      "mean_worldwide_gross_total": "?32.5 Cr"
    },
    "strategic_fit": {
      "optimal_genre": "Social Thriller",
      "key_success_factors": [
        "Moderate Star Power",
        "Festive Multiplier (Republic Day Holiday)",
        "Content Innovation"
      ]
    },
    "market_verdict": "Average"
  }
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` - The `movieId` does not exist, or the entity is owned by another user (indistinguishable by design). Enforced before any cached prediction is served.

**Note:** Mock implementation returns random values between $50M-$150M

---

### 28. Get Protagonist-Antagonist Conflict Balance

**Endpoint:** `GET /api/analytics/{movieId}/conflict-balance`

**Description:** Get the "Protagonist-Antagonist Conflict Balance" narrative-structure score for a movie, derived from its `synopsis` (see [Create](#3-create-managed-entity) / [Update Managed Entity](#4-update-managed-entity)) via the LLM. The `movieId` must reference a movie entity owned by the caller.

The LLM is asked only for qualitative judgment — four ordinal ratings (1-5) and a short rationale — never the final score. Per the catalog decision (Direction: Positive, Impact +25% to +35%, read as fixed bounds on the score itself — same convention as [Get Narrative Novelty](#29-get-high-concept-narrative-novelty)), `balanceScore` is an affine remap of the normalized ratings into `[0.25, 0.35]` — the floor is never breached even for the weakest antagonist, and the ceiling is never exceeded even for the strongest one. It is driven entirely by antagonist quality — `protagonistPower` is returned for context but deliberately excluded from the formula, since a symmetric protagonist-vs-antagonist gap would penalize a dominant antagonist, contradicting the "positive" direction:

```
normalizedBalance = (antagonistPower - 1) / 4 * 0.5
                   + (antagonistMotivationClarity - 1) / 4 * 0.25
                   + (stakesEscalation - 1) / 4 * 0.25

balanceScore = 0.25 + normalizedBalance * 0.10
```

`0.35` means a maximally strong, clearly motivated antagonist with fully escalating stakes; `0.25` means the opposite (or no credible antagonist at all). A rating the LLM omits, mis-formats, or puts out of range (e.g. `"NA"`, `0`) defaults to `1` (the floor) rather than failing the whole request — see [Narrative Novelty](#29-get-high-concept-narrative-novelty) for the identical tolerance policy.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `movieId` - Movie entity ID (e.g., 1)

**Example Request:**
```
GET /api/analytics/11/conflict-balance
```

**Response:**
```json
{
  "movieId": 11,
  "conflictBalance": {
    "protagonistPower": 4,
    "antagonistPower": 5,
    "antagonistMotivationClarity": 4,
    "stakesEscalation": 5,
    "rationale": "The antagonist commands superior resources and a clear, escalating motive, keeping the protagonist consistently on the back foot.",
    "balanceScore": 0.34375
  }
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` - The `movieId` does not exist, or the entity is owned by another user (indistinguishable by design). Enforced before any cached score is served.
- `400 Bad Request` - The movie has no `synopsis` set, or the LLM response could not be parsed into valid ratings.

**Note:** Like [Get Box Office Prediction](#27-get-box-office-prediction), the result is cached per `movieId` for the life of the server process — editing the movie's `synopsis` afterward does not invalidate an already-cached score.

---

### 29. Get High-Concept Narrative Novelty

**Endpoint:** `GET /api/analytics/{movieId}/narrative-novelty`

**Description:** Get the "High-Concept Narrative Novelty" score for a movie, derived from its `synopsis` (see [Create](#3-create-managed-entity) / [Update Managed Entity](#4-update-managed-entity)) via the LLM. The `movieId` must reference a movie entity owned by the caller.

The LLM is asked only for qualitative judgment — four ordinal ratings (1-5) and a short rationale — never the final score. Per the catalog decision (Direction: Positive, Impact +30% to +45%, read as fixed bounds on the score itself rather than just its weight), `noveltyScore` is an affine remap of the normalized ratings into `[0.30, 0.45]` — the floor is never breached even for the most generic premise, and the ceiling is never exceeded even for the most novel one:

```
normalizedNovelty = (worldBuildingDistinctiveness - 1) / 4 * 0.40
                   + (premiseClarity - 1) / 4 * 0.25
                   + (hookMemorability - 1) / 4 * 0.20
                   + (1 - (conceptualCollisionRisk - 1) / 4) * 0.15

noveltyScore = 0.30 + normalizedNovelty * 0.15
```

`conceptualCollisionRisk` is inverted before weighting — a premise that closely resembles a specific existing film scores lower novelty. `0.45` means a highly distinctive, clearly-pitchable, memorable premise with no resemblance to an existing film; `0.30` means the opposite (a generic, derivative premise).

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `movieId` - Movie entity ID (e.g., 1)

**Example Request:**
```
GET /api/analytics/11/narrative-novelty
```

**Response:**
```json
{
  "movieId": 11,
  "narrativeNovelty": {
    "premiseClarity": 4,
    "worldBuildingDistinctiveness": 5,
    "hookMemorability": 4,
    "conceptualCollisionRisk": 2,
    "rationale": "The premise compresses into a single vivid pitch and builds a distinctive world with only a loose resemblance to prior films.",
    "noveltyScore": 0.4275
  }
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` - The `movieId` does not exist, or the entity is owned by another user (indistinguishable by design). Enforced before any cached score is served.
- `400 Bad Request` - The movie has no `synopsis` set, or the LLM response could not be parsed into valid ratings.

**Note:** Like [Get Conflict Balance](#28-get-protagonist-antagonist-conflict-balance), the result is cached per `movieId` for the life of the server process — editing the movie's `synopsis` afterward does not invalidate an already-cached score.

---

## Movie Audience APIs

Audience-size analytics over tracked `MOVIE` entities: unique posters per language, per movie (with per-user engagement), and how a movie's audience compares to similarly-budgeted movies. Only mentions with a non-zero sentiment score are counted. Movies are scoped to the caller's own entities, same as [Entity Management APIs](#entity-management-apis).

### 29a. Get Language Audience

**Endpoint:** `GET /api/movies/audience`

**Description:** Total unique users who posted about any tracked movie in a given `language`, counting a user only once even if they posted about several movies in that language.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `language` *(required)* — movie language to aggregate across, e.g. `Tamil`.
- `ownerId` — (admin only) scope to this user's movies; a non-admin who supplies it gets `403 Forbidden`. Optional.

**Example Request:**
```
GET /api/movies/audience?language=Tamil
```

**Response:**
```json
{
  "language": "Tamil",
  "movieCount": 3,
  "uniqueAudienceCount": 18420,
  "movieNames": ["Movie A", "Movie B", "Movie C"]
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No movie entities exist for `language` (scoped to the caller, or to `ownerId` for an admin).

---

### 29b. Get Movie Audience Detail

**Endpoint:** `GET /api/movies/audience/detail`

**Description:** Every unique user who posted about `movieName` in `language`, with each user's post count, engagement ratio (their share of the movie's qualifying posts), average sentiment score, and positive-sentiment ratio. Sorted by post count descending and capped at `limit`.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `language` *(required)* — the movie's language.
- `movieName` *(required)* — movie name to look up.
- `ownerId` — (admin only) scope to this user's movies; a non-admin who supplies it gets `403 Forbidden`. Optional.
- `limit` — max number of users to return. Default `100`, max `500`. Optional.

**Example Request:**
```
GET /api/movies/audience/detail?language=Tamil&movieName=Movie%20A&limit=50
```

**Response:**
```json
{
  "movieName": "Movie A",
  "language": "Tamil",
  "uniqueAudienceCount": 640,
  "totalPosts": 1120,
  "users": [
    {
      "author": "user_handle",
      "postCount": 14,
      "engagementRatio": 0.0125,
      "averageSentimentScore": 0.62,
      "positiveRatio": 0.86
    }
  ]
}
```

**Response fields:**
- `uniqueAudienceCount` — total distinct posters for the movie (not capped by `limit`; only the `users` array is).
- `totalPosts` — total qualifying (non-zero sentiment) posts about the movie, across all users.
- `users[].engagementRatio` — this user's `postCount` divided by `totalPosts` — their share of the whole conversation.
- `users[].positiveRatio` — fraction of this user's own posts about the movie rated `POSITIVE`.

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No movie named `movieName` exists in `language` (scoped to the caller, or to `ownerId` for an admin).

---

### 29c. Get Budget Comparison

**Endpoint:** `GET /api/movies/audience/budget-comparison`

**Description:** Benchmarks `movieName` against other tracked movies budgeted within ±50% of it (any language, unless `language` is passed), each with its own audience size, to show how the target movie's audience compares to comparable-budget peers.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `movieName` *(required)* — movie to benchmark.
- `language` — restrict lookup of `movieName` to this language; required if more than one movie shares that name across languages. Optional.
- `ownerId` — (admin only) scope to this user's movies; a non-admin who supplies it gets `403 Forbidden`. Optional.

**Example Request:**
```
GET /api/movies/audience/budget-comparison?movieName=Movie%20A&language=Tamil
```

**Response:**
```json
{
  "targetMovieName": "Movie A",
  "targetLanguage": "Tamil",
  "targetBudget": 5000000.0,
  "targetUniqueAudienceCount": 640,
  "targetTotalPosts": 1120,
  "targetAudiencePercentileInRange": 84.5,
  "budgetRangeMinUsd": 2500000.0,
  "budgetRangeMaxUsd": 7500000.0,
  "comparableMovies": [
    {
      "movieName": "Movie D",
      "language": "Telugu",
      "budget": 5500000.0,
      "uniqueAudienceCount": 758,
      "totalPosts": 1310,
      "audiencePercentileInRange": 100.0
    }
  ]
}
```

**Response fields:**
- `targetAudiencePercentileInRange` — the target's `uniqueAudienceCount` as a percentage of the highest audience count across the target and every comparable movie (`100` = top of the range); `null` when every movie in the range has zero qualifying audience.
- `comparableMovies` — excludes the target itself, sorted by `uniqueAudienceCount` descending. Each entry's `audiencePercentileInRange` is computed against the same range maximum as the target's.

**Status Code:** `200 OK`

**Error Responses:**
- `404 Not Found` — No movie named `movieName` exists (in `language`, if supplied; scoped to the caller, or to `ownerId` for an admin).
- `400 Bad Request` — `movieName` matches more than one movie and `language` was not supplied to disambiguate, or the resolved movie has no `budget` recorded.

---

## Alerts APIs

Sentiment alerts are produced by `SentimentAlertService`, which runs two background detectors:

- **`SPIKE`** — every 5 minutes, scans each managed entity's rolling 60-minute window (10-mention minimum, 30-minute dedup window). Per-user [alert rules](#alert-rules-apis) drive the threshold: a rule fires when the negative-sentiment ratio rises by at least `threshold` over the 7-day baseline (e.g. `0.10`), and the resulting alert is tagged with the owning user (`ownerUserId`). When no rule applies to an entity, it falls back to the default behavior — fire when the ratio exceeds 1.5x the baseline — and the alert is left un-owned (`ownerUserId: null`).
- **`INFLUENCER_NEGATIVE`** — every 1 minute, picks up newly inserted `NEGATIVE` mentions (id-based watermark, bulk-insert friendly) whose author appears in the top-50 spreader list for any of the managed entity's keywords. Spreader lookups are cached for 10 minutes per keyword. If users have `INFLUENCER_NEGATIVE` [alert rules](#alert-rules-apis) for the entity, one owned alert is raised per such user; otherwise a single un-owned alert is raised.

After an alert is persisted, `AlertDispatcher` fans it out to two async channels (failures are caught and logged — they do not block alert persistence):

- **Email** — `EmailChannel` interface with a log-only `NoopEmailChannel` `@Component` shipped by default (swap in SendGrid in prod). Subject is `[Aura] {entityName} negative spike`; body lists the top 3 most recent negative mentions for the entity with their permalinks.
- **Webhook** — `WebhookChannel` POSTs the alert JSON to every user's configured `alertWebhookUrl` (see [Set Alert Webhook URL](#31-set-alert-webhook-url)).

### Morning Digest

`MorningDigestService` runs a `@Scheduled(cron = "0 * * * * *")` job (fires every minute at :00) that delivers a per-user overnight digest at 8:00 AM in each user's configured timezone.

**How it works:**

1. Each tick iterates all users and converts the current UTC time to the user's `timezone` (stored on the `users` table; defaults to `UTC`). If the local hour is 8 and the minute is 0, the user is eligible.
2. For each eligible user, the service looks up every entity the user tracks (via `user_entity_views`) and calls `WhatsChangedService.computeDelta(userId, entityId)` for each one.
3. Entities with no changes (zero new mentions and zero sentiment delta) are filtered out. If nothing changed overnight, no email is sent.
4. A headline is picked from the most attention-grabbing entity — super-spreader activity takes priority, then negative mentions (weighted 3x), then total mentions — producing an unpredictable subject line like:
   - `Your overnight Aura brief: Galaxy Quest picked up 7 negative mentions`
   - `Your overnight Aura brief: Emma Stone has 2 new super-spreader mentions`
   - `Your overnight Aura brief: Inception 2 has 14 new mentions`
5. The digest is dispatched via `EmailChannel.sendDigest(user, subject, entries)`. The default `NoopEmailChannel` logs the full digest to console; swap in a real implementation (SendGrid, SES, etc.) in production.

**User timezone:** Each user has a `timezone` column (IANA zone ID, e.g. `America/New_York`, `Asia/Kolkata`, `UTC`). Invalid or null values fall back to UTC. Set it during registration or via a user-settings endpoint.

**Digest body** includes per-entity: sentiment score delta, new mention count, new negative count, new super-spreader count, and competitor deltas (if any).

All routes below are JWT-protected — pass `Authorization: Bearer {jwt_token}`.

### 27a. Create Alert

**Endpoint:** `POST /api/alerts`

**Description:** Manually create a sentiment alert for a managed entity. This is the programmatic/internal counterpart to the background detectors — useful for testing or for external systems that detect anomalies outside of the built-in spike/influencer logic. Returns `409 Conflict` (empty body) if a duplicate alert already exists.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "managedEntityId": 1,
  "kind": "SPIKE",
  "currentValue": 0.5,
  "baselineValue": 0.2,
  "sourceMentionId": null,
  "matchedAuthor": null,
  "permalink": null
}
```

**Validation:**
- `managedEntityId` — required.
- `kind` — required; one of `SPIKE`, `INFLUENCER_NEGATIVE`.
- `currentValue` / `baselineValue` — the rolling and baseline negative-sentiment ratios (relevant for `SPIKE`; set both to `0.0` for `INFLUENCER_NEGATIVE`).
- `sourceMentionId`, `matchedAuthor`, `permalink` — optional; typically populated for `INFLUENCER_NEGATIVE` alerts.

**Response:**
```json
{
  "id": 43,
  "managedEntityId": 1,
  "ownerUserId": null,
  "entityName": "The Quantum Paradox",
  "kind": "SPIKE",
  "status": "OPEN",
  "triggeredAt": "2026-05-21T12:10:00Z",
  "currentValue": 0.5,
  "baselineValue": 0.2,
  "sourceMentionId": null,
  "matchedAuthor": null,
  "permalink": null,
  "ackedAt": null,
  "ackedBy": null,
  "dismissedAt": null,
  "dismissedBy": null,
  "dismissReason": null,
  "reason": "Negative-sentiment ratio rose to 50% (baseline 20%) for The Quantum Paradox"
}
```

**Status Codes:**
- `201 Created` — Alert created.
- `409 Conflict` — Duplicate alert (empty body).

---

### 28. List Alerts

**Endpoint:** `GET /api/alerts`

**Description:** Paged list of alerts, sorted by `triggeredAt` descending (most recent first). Both filters are optional and AND-combined when present.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**
- `entityId` — Filter to a single managed entity (Optional)
- `status` — Filter by status: `OPEN`, `ACKED`, `DISMISSED` (Optional)
- `page` — Page number (default: `0`)
- `size` — Page size (default: `20`)

**Example Request:**
```
GET /api/alerts?entityId=1&status=OPEN&page=0&size=20
```

**Response:**
```json
{
  "content": [
    {
      "id": 42,
      "managedEntityId": 1,
      "ownerUserId": 7,
      "entityName": "The Quantum Paradox",
      "kind": "INFLUENCER_NEGATIVE",
      "status": "OPEN",
      "triggeredAt": "2026-05-21T12:00:00Z",
      "currentValue": 0.0,
      "baselineValue": 0.0,
      "sourceMentionId": 9123,
      "matchedAuthor": "alice",
      "permalink": "https://x.com/alice/status/9123",
      "ackedAt": null,
      "ackedBy": null,
      "dismissedAt": null,
      "dismissedBy": null,
      "dismissReason": null,
      "reason": "Top-50 spreader alice posted a negative mention about The Quantum Paradox"
    },
    {
      "id": 41,
      "managedEntityId": 1,
      "ownerUserId": null,
      "entityName": "The Quantum Paradox",
      "kind": "SPIKE",
      "status": "OPEN",
      "triggeredAt": "2026-05-21T11:35:00Z",
      "currentValue": 0.5,
      "baselineValue": 0.2,
      "sourceMentionId": null,
      "matchedAuthor": null,
      "permalink": null,
      "ackedAt": null,
      "ackedBy": null,
      "dismissedAt": null,
      "dismissedBy": null,
      "dismissReason": null,
      "reason": "Negative-sentiment ratio rose to 50% (baseline 20%) for The Quantum Paradox"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20
  },
  "totalElements": 2,
  "totalPages": 1,
  "last": true
}
```

**Status Code:** `200 OK`

**Field Notes:**
- `kind` is one of `SPIKE`, `INFLUENCER_NEGATIVE`.
- `status` is one of `OPEN`, `ACKED`, `DISMISSED`.
- `ownerUserId` is the user whose [alert rule](#alert-rules-apis) triggered the alert, or `null` for alerts raised by the default fallback thresholds (no matching rule). Manually-created alerts are un-owned (`null`).
- `currentValue` / `baselineValue` are the rolling and 7-day negative-sentiment ratios for `SPIKE` alerts; both are `0.0` for `INFLUENCER_NEGATIVE`.
- `sourceMentionId`, `matchedAuthor`, `permalink` are populated for `INFLUENCER_NEGATIVE` and `null` for `SPIKE`.
- `reason` is a server-rendered, 1-line human-readable summary suitable for direct display.

---

### 29. Acknowledge Alert

**Endpoint:** `POST /api/alerts/{id}/ack`

**Description:** Mark an alert as acknowledged by the calling user. Sets `status=ACKED`, `ackedAt` to the current server time, and `ackedBy` to the authenticated username. Body is not required.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `id` — Alert ID

**Example Request:**
```
POST /api/alerts/42/ack
```

**Response:**
```json
{
  "id": 42,
  "managedEntityId": 1,
  "entityName": "The Quantum Paradox",
  "kind": "INFLUENCER_NEGATIVE",
  "status": "ACKED",
  "triggeredAt": "2026-05-21T12:00:00Z",
  "currentValue": 0.0,
  "baselineValue": 0.0,
  "sourceMentionId": 9123,
  "matchedAuthor": "alice",
  "permalink": "https://x.com/alice/status/9123",
  "ackedAt": "2026-05-21T12:05:11Z",
  "ackedBy": "ops_user",
  "dismissedAt": null,
  "dismissedBy": null,
  "dismissReason": null,
  "reason": "Top-50 spreader alice posted a negative mention about The Quantum Paradox"
}
```

**Status Codes:**
- `200 OK` — Alert acknowledged.
- `404 Not Found` — No alert with the given id.

---

### 30. Dismiss Alert

**Endpoint:** `POST /api/alerts/{id}/dismiss`

**Description:** Dismiss an alert with a required reason. Sets `status=DISMISSED`, `dismissedAt` to the current server time, `dismissedBy` to the authenticated username, and `dismissReason` to the supplied text.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `id` — Alert ID

**Request Body:**
```json
{
  "reason": "false positive — known reviewer"
}
```

**Validation:**
- `reason` is required and must be non-blank.

**Response:**
```json
{
  "id": 42,
  "managedEntityId": 1,
  "entityName": "The Quantum Paradox",
  "kind": "INFLUENCER_NEGATIVE",
  "status": "DISMISSED",
  "triggeredAt": "2026-05-21T12:00:00Z",
  "currentValue": 0.0,
  "baselineValue": 0.0,
  "sourceMentionId": 9123,
  "matchedAuthor": "alice",
  "permalink": "https://x.com/alice/status/9123",
  "ackedAt": null,
  "ackedBy": null,
  "dismissedAt": "2026-05-21T12:07:42Z",
  "dismissedBy": "ops_user",
  "dismissReason": "false positive — known reviewer",
  "reason": "Top-50 spreader alice posted a negative mention about The Quantum Paradox"
}
```

**Status Codes:**
- `200 OK` — Alert dismissed.
- `400 Bad Request` — `reason` missing or blank.
- `404 Not Found` — No alert with the given id.

---

### 31. Set Alert Webhook URL

**Endpoint:** `PUT /api/users/me/webhook`

**Description:** Set (or clear) the webhook URL where the calling user wants alert JSON delivered. When `WebhookChannel` dispatches an alert, it POSTs the alert payload to this URL with `Content-Type: application/json`. Pass an empty string or `null` to disable webhook delivery for the user.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "webhookUrl": "https://hooks.example.com/aura/alerts/abc123"
}
```

**Validation:**
- `webhookUrl` — max length 2048 characters. Blank values are stored as `null` (disables webhook delivery).

**Response:**
```json
{
  "username": "ops_user",
  "alertWebhookUrl": "https://hooks.example.com/aura/alerts/abc123"
}
```

**Status Codes:**
- `200 OK` — Webhook URL updated.
- `404 Not Found` — Authenticated user record not found.

**Webhook Payload (sent to `webhookUrl` on each alert):**
```json
{
  "id": 42,
  "managedEntityId": 1,
  "entityName": "The Quantum Paradox",
  "kind": "INFLUENCER_NEGATIVE",
  "status": "OPEN",
  "triggeredAt": "2026-05-21T12:00:00Z",
  "currentValue": 0.0,
  "baselineValue": 0.0,
  "sourceMentionId": 9123,
  "matchedAuthor": "alice",
  "permalink": "https://x.com/alice/status/9123"
}
```

---

## Alert Rules APIs

Alert rules let each user own how sentiment alerts fire for them — e.g. *"alert me when competitor X's negative sentiment rises by more than 0.10"*. The background detectors in [Alerts APIs](#alerts-apis) load these rules per user and fall back to built-in defaults when no rule applies.

An `AlertRule` has:

- `userId` — the owning user. Always derived from the authenticated principal; never accepted in the request body. A user can only see and modify their own rules.
- `entityId` — the managed entity the rule targets, or `null` for a **wildcard** rule that applies to every entity the user watches.
- `kind` — `SPIKE` or `INFLUENCER_NEGATIVE`.
- `threshold` — for `SPIKE`, the minimum absolute rise in negative-sentiment ratio over the 7-day baseline that triggers an alert (e.g. `0.10`). Ignored for `INFLUENCER_NEGATIVE`, which is presence-based.
- `channels` — list of delivery channel hints (e.g. `["EMAIL", "WEBHOOK"]`). Persisted and returned as-is; channel-aware dispatch is not yet wired in (`AlertDispatcher` currently fans every alert out to email and webhook).
- `enabled` — when `false`, the rule is stored but ignored by the detectors.

When multiple of a user's rules match the same entity (e.g. an entity-specific rule plus a wildcard rule), the detector uses the most sensitive (lowest) `threshold`, so each user receives at most one alert per entity per dedup window. Alerts produced from a rule are tagged with `ownerUserId`; default-fallback alerts (no matching rule) are left un-owned.

All routes are JWT-protected — pass `Authorization: Bearer {jwt_token}`.

### 31a. List Alert Rules

**Endpoint:** `GET /api/alert-rules`

**Description:** List all alert rules owned by the authenticated user.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Response:**
```json
[
  {
    "id": 5,
    "userId": 7,
    "entityId": 1,
    "kind": "SPIKE",
    "threshold": 0.10,
    "channels": ["EMAIL", "WEBHOOK"],
    "enabled": true
  },
  {
    "id": 6,
    "userId": 7,
    "entityId": null,
    "kind": "INFLUENCER_NEGATIVE",
    "threshold": 0.0,
    "channels": ["WEBHOOK"],
    "enabled": true
  }
]
```

**Status Code:** `200 OK`

---

### 31b. Get Alert Rule

**Endpoint:** `GET /api/alert-rules/{id}`

**Description:** Fetch a single alert rule by id. Only the owner can read it.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `id` — Alert rule ID

**Response:** Same shape as a single element above.

**Status Codes:**
- `200 OK` — Rule found.
- `404 Not Found` — No rule with that id owned by the calling user.

---

### 31c. Create Alert Rule

**Endpoint:** `POST /api/alert-rules`

**Description:** Create an alert rule owned by the authenticated user.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "entityId": 1,
  "kind": "SPIKE",
  "threshold": 0.10,
  "channels": ["EMAIL", "WEBHOOK"],
  "enabled": true
}
```

**Validation:**
- `kind` — required; one of `SPIKE`, `INFLUENCER_NEGATIVE`.
- `threshold` — must be zero or positive.
- `entityId` — optional; omit (or `null`) for a wildcard rule. When provided, it must reference an existing managed entity, otherwise `400 Bad Request`.
- `channels` — optional; defaults to an empty list.
- `enabled` — optional; defaults to `true`.

**Response:** The created rule (same shape as a list element), including its assigned `id` and the resolved `userId`.

**Status Codes:**
- `201 Created` — Rule created.
- `400 Bad Request` — Validation failure (e.g. missing `kind`, negative `threshold`, unknown `entityId`).

---

### 31d. Update Alert Rule

**Endpoint:** `PUT /api/alert-rules/{id}`

**Description:** Replace the fields of an existing rule owned by the authenticated user. The body is the same as create; `userId` is never changed.

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `id` — Alert rule ID

**Request Body:** Same shape as [Create Alert Rule](#31c-create-alert-rule).

**Response:** The updated rule.

**Status Codes:**
- `200 OK` — Rule updated.
- `400 Bad Request` — Validation failure (e.g. unknown `entityId`).
- `404 Not Found` — No rule with that id owned by the calling user.

---

### 31e. Delete Alert Rule

**Endpoint:** `DELETE /api/alert-rules/{id}`

**Description:** Delete a rule owned by the authenticated user. Once deleted, the affected entity falls back to default thresholds (or another of the user's matching rules).

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `id` — Alert rule ID

**Example:**
```
DELETE /api/alert-rules/5
```

**Status Codes:**
- `204 No Content` — Rule deleted.
- `404 Not Found` — No rule with that id owned by the calling user.

---

## Workspace APIs

Backup and restore the authenticated user's entire workspace — their reply templates, alert rules, playbooks, and tracked entities — as a single document.

> **Format is intentionally proprietary.** Export and import speak one Aura-specific JSON shape (`format: "aura-workspace-export"`). There is deliberately **no** per-resource CSV or other interoperable export: backing up and restoring within Aura is one request, but extracting individual resources for migration to another tool is not supported. This "easy backup, hard exit" shape is a product retention decision, not a technical limitation — **flagged for product review** (see `WorkspaceController`).

All routes are JWT-protected — pass `Authorization: Bearer {jwt_token}`.

### 31f. Get Workspace Impact

**Endpoint:** `GET /api/workspace/impact`

**Description:** The authenticated user's accumulated investment in their workspace, reflected back as counters plus display-ready highlight sentences (e.g. `"Your playbook library has handled 12 crises."`). Intended for the dashboard header and the morning digest, so the value the user has built up over time is visible rather than silent.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Response:**
```json
{
  "entitiesWatched": 6,
  "templateCount": 4,
  "draftsSavedByTemplates": 27,
  "playbookCount": 12,
  "favoritePlaybookCount": 3,
  "repliesPosted": 58,
  "alliesMobilized": 214,
  "abuseReportsFiled": 9,
  "abuseReportsUpheld": 5,
  "highlights": [
    "Your playbook library has handled 12 crises.",
    "You've rallied 214 allies across mobilize actions.",
    "5 of your abuse reports have been upheld."
  ]
}
```

**Response fields:**
- `entitiesWatched` — distinct entities the user actively views.
- `templateCount` / `draftsSavedByTemplates` — reply templates authored, and how many times those templates have seeded a reply in total.
- `playbookCount` / `favoritePlaybookCount` — crisis playbooks built, and how many are starred for quick reuse.
- `repliesPosted` — replies the user has actually posted to platforms (see [Post Reply](#24-post-reply)).
- `alliesMobilized` — supporters rallied across all [Mobilize Allies](#26-mobilize-allies) actions.
- `abuseReportsFiled` / `abuseReportsUpheld` — abuse reports filed, and how many of those were upheld (posts removed) by the platform.
- `highlights` — display-ready sentences for the non-zero metrics, ordered most-rewarding first. A client can render these directly without re-deriving copy.

**Status Code:** `200 OK`

---

### 31g. Export Workspace

**Endpoint:** `GET /api/workspace/export`

**Description:** Returns a single JSON bundle of everything the authenticated user owns: reply templates, alert rules, playbooks (crisis plans they created), and tracked entities (the entities they've viewed, with last-seen timestamps). The response is sent with `Content-Disposition: attachment; filename="aura-workspace.json"` so browsers save it as a backup file.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Response:**
```json
{
  "format": "aura-workspace-export",
  "version": 1,
  "exportedAt": "2026-05-31T12:00:00Z",
  "owner": "alice",
  "templates": [
    {
      "name": "Calm apology",
      "body": "Thanks for flagging this — we're on it.",
      "tone": "empathetic",
      "useCount": 12,
      "createdAt": "2026-05-01T09:30:00Z"
    }
  ],
  "alertRules": [
    {
      "entityId": 1,
      "kind": "SPIKE",
      "threshold": 0.10,
      "channels": ["EMAIL", "WEBHOOK"],
      "enabled": true
    }
  ],
  "playbooks": [
    {
      "entityId": 1,
      "mentionId": 42,
      "title": "Trailer backlash response",
      "planText": "Step 1 ...",
      "tags": ["launch", "pr"],
      "isFavorite": true,
      "createdAt": "2026-05-10T15:00:00Z"
    }
  ],
  "trackedEntities": [
    {
      "entityId": 1,
      "lastSeenAt": "2026-05-30T18:45:00Z"
    }
  ]
}
```

**Status Code:** `200 OK`

---

### 31h. Import Workspace

**Endpoint:** `POST /api/workspace/import`

**Description:** Restores a previously exported bundle into the authenticated user's account. The body must be a document produced by `GET /api/workspace/export` (same `format` and `version`).

Import is **additive** and never deletes existing data:
- **Templates, alert rules, playbooks** are recreated as new rows owned by the calling user. Re-importing the same bundle duplicates them. Server-assigned IDs and ownership in the file are ignored — ownership is always the authenticated user.
- **Tracked entities** are upserted by `entityId` (the user's last-seen timestamp for that entity is created or updated).

**Headers:**
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
```

**Request Body:** A `aura-workspace-export` document (see the export response above).

**Response:**
```json
{
  "templatesImported": 1,
  "alertRulesImported": 1,
  "playbooksImported": 1,
  "trackedEntitiesImported": 1
}
```

**Status Codes:**
- `200 OK` — Bundle restored; counts reflect what was imported.
- `400 Bad Request` — Missing body, or `format`/`version` does not match the proprietary Aura format.

---

## Marketing Aggregation APIs

The marketing aggregation APIs aggregate data from the upstream AuraMath service across all keywords matching a set of filters. Instead of querying per keyword, these endpoints let you query by **language** (e.g., Tamil, Telugu), **industry** (e.g., Tollywood, Kollywood), **genre** (e.g., action, drama), **state**, or **entity ID** and get a unified, deduplicated result.

All endpoints are JWT-protected and mounted under `/api/marketing/aggregate/`.

> **Tier-gated (DIAMOND).** Aggregated Intel (`/api/marketing/aggregate/**`) requires the **DIAMOND**
> tier; a lower-tier caller still gets `200 OK` with an `EntitledResponse` whose `entitled=false` and a
> masked `preview` of the aggregation. Admins are always entitled. See
> [Premium Feature Tier Gating](#premium-feature-tier-gating).

**Common Query Parameters (at least one required):**

| Parameter | Type | Description |
| --- | --- | --- |
| `language` | String | Filter keywords by language (e.g., `Tamil`, `Telugu`) |
| `industry` | String | Filter keywords by movie industry (e.g., `Tollywood`, `Kollywood`) |
| `state` | String | Filter keywords by state/region |
| `genre` | String | Filter keywords by genre (e.g., `action`, `drama`) |
| `entityId` | Long | Filter keywords belonging to a specific managed entity |
| `groupBy` | String | `keyword` (or `genre` for genre endpoints) to group results instead of flat list |

If none of these filters are provided, the endpoint returns `400 Bad Request`.

When `entityId` is supplied, that entity must be **owned by the caller** — otherwise the endpoint returns `404 Not Found` (the same response as for a non-existent entity, so existence is never leaked). The non-`entityId` filters (`language`, `industry`, `state`, `genre`) aggregate across keywords and are not entity-scoped.

**Response format:**

Every endpoint below is wrapped in an [`EntitledResponse`](#premium-feature-tier-gating) envelope. For an **entitled** caller (DIAMOND or admin) the aggregation is the `data` field (`entitled: true`, `requiredTier: "DIAMOND"`, `preview: null`); the per-endpoint JSON examples that follow show that `data` payload. A caller **below DIAMOND** instead gets `200 OK` with `entitled: false`, `data: null`, and a masked `preview` (lists truncated to a teaser, numbers bucketed, strings starred), e.g.:

```json
{ "entitled": false, "requiredTier": "DIAMOND", "data": null,
  "preview": [ { "author": "★★★★★", "primaryPlatform": "★★★★★", "influenceTier": "★★★★★" } ] }
```

The shape of the `data` payload depends on `groupBy`:
- **Default (flat):** A deduplicated JSON array merging results from all matching keywords. Duplicates are detected by `globalUserId`, `userId`, `author`, `username`, or `id` fields.
- **Grouped (`?groupBy=keyword`):** A JSON object keyed by keyword (or genre), each value being the array of results for that keyword.

---

### 32. Aggregated Top Spreaders

**Endpoint:** `GET /api/marketing/aggregate/top-spreaders`

**Description:** Retrieve the union of top spreaders across all keywords matching the given filters. For each matching keyword, calls upstream `GET /api/marketing/top-50-spreaders/{keyword}` and merges the results.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Example Requests:**
```bash
# All top spreaders for Tamil movies (flat)
GET /api/marketing/aggregate/top-spreaders?language=Tamil

# All top spreaders for Tollywood, grouped by keyword
GET /api/marketing/aggregate/top-spreaders?industry=Tollywood&groupBy=keyword

# Top spreaders for a specific entity
GET /api/marketing/aggregate/top-spreaders?entityId=1

# Multiple filters
GET /api/marketing/aggregate/top-spreaders?language=Telugu&industry=Tollywood
```

**Response (flat, default):**
```json
[
  {
    "author": "cinephile_arjun",
    "primaryPlatform": "twitter",
    "influenceTier": "mega"
  },
  {
    "author": "kollywood_news",
    "primaryPlatform": "instagram",
    "influenceTier": "macro"
  },
  {
    "author": "film_buff_42",
    "primaryPlatform": "youtube",
    "influenceTier": "micro"
  }
]
```

**Response (grouped, `?groupBy=keyword`):**
```json
{
  "karuppu": [
    { "author": "cinephile_arjun", "primaryPlatform": "twitter", "influenceTier": "mega" },
    { "author": "kollywood_news", "primaryPlatform": "instagram", "influenceTier": "macro" }
  ],
  "surya": [
    { "author": "kollywood_news", "primaryPlatform": "instagram", "influenceTier": "macro" },
    { "author": "film_buff_42", "primaryPlatform": "youtube", "influenceTier": "micro" }
  ]
}
```

**Status Codes:**
- `200 OK`
- `400 Bad Request` — no filter provided

---

### 33. Aggregated Viral Seeds

**Endpoint:** `GET /api/marketing/aggregate/viral-seeds`

**Description:** Retrieve the union of viral seeds across all keywords matching the given filters. For each matching keyword, calls upstream `GET /api/marketing/viral-seeds?keyword={keyword}` and merges the results.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Example Request:**
```bash
GET /api/marketing/aggregate/viral-seeds?language=Tamil
```

**Response (flat, default):**
```json
[
  { "userId": "seed_user_1" },
  { "userId": "seed_user_2" }
]
```

**Status Codes:**
- `200 OK`
- `400 Bad Request` — no filter provided

---

### 34. Aggregated Aspect Drivers

**Endpoint:** `GET /api/marketing/aggregate/aspect-drivers`

**Description:** Retrieve the union of aspect drivers across all keywords matching the given filters. For each matching keyword, calls upstream `GET /api/marketing/aspect-drivers/{keyword}` and merges the results.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Example Request:**
```bash
GET /api/marketing/aggregate/aspect-drivers?entityId=1
```

**Response (flat, default):**
```json
[
  { "id": "driver_1" },
  { "id": "driver_2" }
]
```

**Status Codes:**
- `200 OK`
- `400 Bad Request` — no filter provided

---

### 35. Aggregated Brand Evangelists

**Endpoint:** `GET /api/marketing/aggregate/brand-evangelists`

**Description:** Retrieve the union of brand evangelists across all keywords matching the given filters. For each matching keyword, calls upstream `GET /api/marketing/brand-evangelists/{keyword}` and merges the results.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Example Request:**
```bash
GET /api/marketing/aggregate/brand-evangelists?language=Telugu
```

**Response (flat, default):**
```json
[
  { "author": "evangelist_1" },
  { "author": "evangelist_2" }
]
```

**Status Codes:**
- `200 OK`
- `400 Bad Request` — no filter provided

---

### 36. Aggregated Genre Data

**Endpoint:** `GET /api/marketing/aggregate/genre/{subType}`

**Description:** Aggregate genre-level data across all distinct genres found on keywords matching the given filters. The service finds all matching keywords, collects their distinct `genre` values, and for each genre calls the corresponding upstream endpoint. Results are merged (deduplicated) or grouped by genre.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `subType` — one of: `potential-viewers`, `super-spreaders`, `channel-strategy`

**Query Parameters:**
- Same common filters as above (`language`, `industry`, `state`, `genre`, `entityId`)
- `groupBy=genre` to group results by genre instead of a flat list

**Example Requests:**
```bash
# Potential viewers across all genres in Tamil movies
GET /api/marketing/aggregate/genre/potential-viewers?language=Tamil

# Super spreaders for Kollywood genres, grouped by genre
GET /api/marketing/aggregate/genre/super-spreaders?industry=Kollywood&groupBy=genre

# Channel strategy for a specific entity's genres
GET /api/marketing/aggregate/genre/channel-strategy?entityId=1
```

**Response (flat, default — potential-viewers):**
```json
[
  { "userId": "viewer_1" },
  { "userId": "viewer_2" },
  { "userId": "viewer_3" }
]
```

**Response (grouped, `?groupBy=genre` — super-spreaders):**
```json
{
  "action": [
    { "author": "spreader_1" }
  ],
  "drama": [
    { "author": "spreader_2" },
    { "author": "spreader_3" }
  ]
}
```

**Status Codes:**
- `200 OK`
- `400 Bad Request` — no filter provided, or invalid `subType`

**Validation Error (invalid subType):** rendered by the global error handler as a standard `ErrorResponse`.
```json
{
  "timestamp": "2026-06-14T10:15:00",
  "status": 400,
  "error": "Bad Request",
  "message": "subType must be one of: potential-viewers, super-spreaders, channel-strategy"
}
```

---

### 36a. Get Audience Timing Pattern

**Endpoint:** `GET /api/marketing/audience-patterns/timing`

**Description:** Post volume, unique-author, and engagement (likes + comments) totals for every tracked `MOVIE` entity matching the given filters, bucketed by **UTC hour-of-day** (0-23) and **day-of-week**, plus the top 10 highest-engagement `(day, hour)` slots — the concrete "post here for maximum reach" recommendation for the marketing team. Engagement is pulled from the per-platform ingestion tables (`x_posts`, `youtube_comments`, `reddit_posts`, `instagram_posts`); a mention whose post no longer resolves in its platform table still counts toward `postCount` but contributes zero engagement.

Mounted alongside the [Marketing Aggregation APIs](#marketing-aggregation-apis) and shares the same **DIAMOND** tier gate — see [Premium Feature Tier Gating](#premium-feature-tier-gating).

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**

| Parameter | Type | Description |
| --- | --- | --- |
| `language` | String | Filter to movies in this language (e.g. `Tamil`, `Kannada`) |
| `industry` | String | Filter to movies in this industry (e.g. `Kollywood`, `Sandalwood`) |
| `movieName` | String | Filter to a single movie by name |
| `from` | ISO-8601 datetime | Only mentions posted on/after this instant. Defaults to the full history. |
| `to` | ISO-8601 datetime | Only mentions posted on/before this instant. Defaults to now. |
| `ownerId` | Long | Admin-only: scope to a specific user's movies. Non-admins may not pass this. |

At least one of `language`, `industry`, or `movieName` is required — otherwise `400 Bad Request`.

**Example Requests:**
```bash
# Every Kannada movie
GET /api/marketing/audience-patterns/timing?language=Kannada

# A specific movie
GET /api/marketing/audience-patterns/timing?movieName=Karuppu

# Kollywood, narrowed to a date range
GET /api/marketing/audience-patterns/timing?industry=Kollywood&from=2026-06-01T00:00:00Z&to=2026-08-01T00:00:00Z
```

**Response (entitled, truncated for readability):**
```json
{
  "entitled": true,
  "requiredTier": "DIAMOND",
  "preview": null,
  "data": {
    "scope": "language=Kannada",
    "movieCount": 16,
    "totalPosts": 6378,
    "uniqueAuthors": 4384,
    "totalEngagement": 27144,
    "byHourOfDay": [
      { "hourUtc": 0, "postCount": 190, "uniqueAuthors": 173, "totalLikes": 130, "totalComments": 1, "totalEngagement": 131, "avgEngagementPerPost": 0.689 },
      { "hourUtc": "...", "postCount": "...", "uniqueAuthors": "...", "totalLikes": "...", "totalComments": "...", "totalEngagement": "...", "avgEngagementPerPost": "..." },
      { "hourUtc": 21, "postCount": 452, "uniqueAuthors": 382, "totalLikes": 4247, "totalComments": 23, "totalEngagement": 4270, "avgEngagementPerPost": 9.447 },
      { "hourUtc": 23, "postCount": 342, "uniqueAuthors": 294, "totalLikes": 1227, "totalComments": 5, "totalEngagement": 1232, "avgEngagementPerPost": 3.602 }
    ],
    "byDayOfWeek": [
      { "dayOfWeek": "MONDAY", "postCount": 1071, "uniqueAuthors": 845, "totalLikes": 4347, "totalComments": 56, "totalEngagement": 4403, "avgEngagementPerPost": 4.111 },
      { "dayOfWeek": "...", "postCount": "...", "uniqueAuthors": "...", "totalLikes": "...", "totalComments": "...", "totalEngagement": "...", "avgEngagementPerPost": "..." },
      { "dayOfWeek": "SUNDAY", "postCount": 740, "uniqueAuthors": 599, "totalLikes": 4691, "totalComments": 52, "totalEngagement": 4743, "avgEngagementPerPost": 6.409 }
    ],
    "topTimeSlots": [
      { "dayOfWeek": "SATURDAY", "hourUtc": 21, "postCount": 58, "totalEngagement": 1522, "avgEngagementPerPost": 26.241 },
      { "dayOfWeek": "TUESDAY", "hourUtc": 19, "postCount": 154, "totalEngagement": 1477, "avgEngagementPerPost": 9.591 },
      { "dayOfWeek": "...", "hourUtc": "...", "postCount": "...", "totalEngagement": "...", "avgEngagementPerPost": "..." }
    ]
  }
}
```

**Response (under-tier, locked preview):**
```json
{ "entitled": false, "requiredTier": "DIAMOND", "data": null,
  "preview": { "scope": "★★★★★", "movieCount": "★★★★★", "byHourOfDay": "★★★★★" } }
```

**Response fields:**
- `byHourOfDay` — always a complete 24-entry array (hours 0-23, UTC), even hours with zero posts.
- `byDayOfWeek` — always a complete 7-entry array (`MONDAY`-`SUNDAY`).
- `topTimeSlots` — up to 10 `(dayOfWeek, hourUtc)` combinations ranked by `totalEngagement` descending; slots with zero engagement are omitted entirely rather than padded in.
- `uniqueAuthors` (top-level) counts a poster once across the whole scope, even if they posted about several matching movies or at several different times.

**Status Codes:**
- `200 OK`
- `400 Bad Request` — none of `language`/`industry`/`movieName` provided
- `404 Not Found` — no movies match the given filters (within the caller's ownership scope)

---

### 36b. Get Audience Cohort Pattern

**Endpoint:** `GET /api/marketing/audience-patterns/cohorts`

**Description:** Post volume, unique-author, engagement, and sentiment totals for **every tracked `MOVIE` entity**, grouped into industry or language cohorts and sorted by `totalEngagement` descending — lets the marketing team compare, e.g., Kollywood vs. Bollywood engagement before allocating spend across industries or languages. A mention attributed to two movies in the same cohort counts toward that cohort's `totalPosts` twice (once per movie), the same convention used elsewhere for multi-entity aggregation (see [Get Budget Comparison](#29c-get-budget-comparison)), while `uniqueAuthors` counts a poster in the cohort only once no matter how many of the cohort's movies they posted about.

Mounted alongside the [Marketing Aggregation APIs](#marketing-aggregation-apis) and shares the same **DIAMOND** tier gate — see [Premium Feature Tier Gating](#premium-feature-tier-gating).

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Query Parameters:**

| Parameter | Type | Description |
| --- | --- | --- |
| `groupBy` | `INDUSTRY` \| `LANGUAGE` | **Required.** How to bucket movies. |
| `from` | ISO-8601 datetime | Only mentions posted on/after this instant. Defaults to the full history. |
| `to` | ISO-8601 datetime | Only mentions posted on/before this instant. Defaults to now. |
| `ownerId` | Long | Admin-only: scope to a specific user's movies. Non-admins may not pass this. |

**Example Requests:**
```bash
GET /api/marketing/audience-patterns/cohorts?groupBy=INDUSTRY

GET /api/marketing/audience-patterns/cohorts?groupBy=LANGUAGE&from=2026-01-01T00:00:00Z
```

**Response (entitled):**
```json
{
  "entitled": true,
  "requiredTier": "DIAMOND",
  "preview": null,
  "data": {
    "groupBy": "INDUSTRY",
    "cohortCount": 5,
    "cohorts": [
      {
        "cohort": "Kollywood",
        "movieCount": 18,
        "totalPosts": 25717,
        "uniqueAuthors": 15423,
        "totalLikes": 281946,
        "totalComments": 5738,
        "totalEngagement": 287684,
        "avgEngagementPerPost": 11.187,
        "avgSentimentScore": 74.423,
        "positiveSentimentRatio": 0.700
      },
      {
        "cohort": "Sandalwood",
        "movieCount": 16,
        "totalPosts": 9699,
        "uniqueAuthors": 4384,
        "totalLikes": 40200,
        "totalComments": 462,
        "totalEngagement": 40662,
        "avgEngagementPerPost": 4.192,
        "avgSentimentScore": 73.255,
        "positiveSentimentRatio": 0.798
      },
      {
        "cohort": "Bollywood",
        "movieCount": 4,
        "totalPosts": 2796,
        "uniqueAuthors": 2065,
        "totalLikes": 782,
        "totalComments": 74,
        "totalEngagement": 856,
        "avgEngagementPerPost": 0.306,
        "avgSentimentScore": 66.401,
        "positiveSentimentRatio": 0.474
      }
    ]
  }
}
```

**Response fields:**
- `cohort` — the industry or language value, exactly as stored on the movie; movies with no industry/language recorded are grouped under `"Unspecified"` rather than dropped.
- `movieCount` — every tracked movie in the cohort, regardless of whether it has any mentions yet.
- `avgSentimentScore` / `positiveSentimentRatio` — averaged only over mentions with a non-null sentiment score / sentiment.

**Status Codes:**
- `200 OK`
- `404 Not Found` — no movies exist within the caller's ownership scope

---

### Keyword `genre` Field

The `genre` field was added to `EntityKeyword` to support genre-based aggregation. When creating or updating entity keywords, include the `genre` field to enable the genre aggregation endpoints:

```json
{
  "keywords": [
    {
      "keyword": "karuppu",
      "category": "media.movie",
      "language": "Tamil",
      "industry": "Kollywood",
      "genre": "action"
    }
  ]
}
```

---

## AuraMath Proxy APIs

The following endpoints are thin wrappers over the upstream **AuraMath** service. Each wrapper forwards the request to the corresponding upstream route verbatim and preserves the upstream HTTP status code. Wrapper paths (`/v1/**`) **do not** require JWT authentication — the upstream service is responsible for its own auth. See the [AuraMath Proxy](#auramath-proxy-v1-healthz) section below for configuration, error envelopes, and runtime details.

### 37. Get Viral Seeds

**Endpoint:** `GET /v1/viral-seeds`

**Description:** Get viral seed authors for a keyword. Forwards to upstream `GET /api/marketing/viral-seeds`.

**Authentication:** Not required

**Query Parameters:**
- `keyword` (required) - The keyword to search for viral seeds

**Example Request:**
```
GET /v1/viral-seeds?keyword=fantasy
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### 38. Get Aspect Drivers

**Endpoint:** `GET /v1/aspect-drivers/{keyword}`

**Description:** Get aspect drivers for a keyword. Forwards to upstream `GET /api/marketing/aspect-drivers/{keyword}`.

**Authentication:** Not required

**Path Parameters:**
- `keyword` - The keyword to get aspect drivers for

**Example Request:**
```
GET /v1/aspect-drivers/fantasy
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### Aspect Drivers by Entity

**Endpoint:** `GET /v1/aspect-drivers?entityId={id}`

**Description:** Entity-scoped variant of [#38](#38-get-aspect-drivers): aggregates aspect drivers across **all** of an entity's tracked keywords instead of a single keyword. Forwards to upstream `GET /api/marketing/aspect-drivers?entityId={id}`.

**Authentication:** Not required

**Query Parameters:**
- `entityId` (required) - The `managed_entities` id (opaque string, not assumed numeric)

**Example Request:**
```
GET /v1/aspect-drivers?entityId=29
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### 39. Get Top Spreaders

**Endpoint:** `GET /v1/top-spreaders/{keyword}`

**Description:** Get the top 50 spreaders for a keyword. Forwards to upstream `GET /api/marketing/top-50-spreaders/{keyword}`.

**Authentication:** Not required

**Path Parameters:**
- `keyword` - The keyword to get top spreaders for

**Example Request:**
```
GET /v1/top-spreaders/fantasy
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### 40. Find Lookalikes

**Endpoint:** `POST /v1/find-lookalikes`

**Description:** Find lookalike authors given a seed author. Forwards to upstream `POST /api/marketing/find-lookalikes`. Returns `400` without calling upstream if `seedAuthorId` is missing or blank.

**Authentication:** Not required

**Request Body:**
```json
{
  "seedAuthorId": "author-123"
}
```

**Example Request:**
```
POST /v1/find-lookalikes
Content-Type: application/json

{"seedAuthorId":"author-123"}
```

**Cache:** Not cached

**Status Code:** `200 OK` (upstream status preserved)

**Validation Error (400 Bad Request):**
```json
{ "error": "seedAuthorId is required and must be non-blank" }
```

---

### Lookalike Ranking Diagnostic

**Endpoint:** `GET /v1/find-lookalikes/diff?seedAuthorId={id}&limit={n}`

**Description:** Diagnostic comparison harness (not for production consumption): forwards to upstream `GET /api/marketing/find-lookalikes/diff`, which runs both the legacy and current production lookalike-ranking methods for the same seed and returns them side by side with rank movement. Used for similarity-weight tuning.

**Authentication:** Not required

**Query Parameters:**
- `seedAuthorId` (required) - The seed author to compare rankings for
- `limit` (optional) - Max candidates per method; upstream defaults to `25` when omitted

**Example Request:**
```
GET /v1/find-lookalikes/diff?seedAuthorId=u_182374&limit=25
```

**Cache:** Not cached

**Status Code:** `200 OK` (upstream status preserved)

---

### 41. Get User Profile

**Endpoint:** `GET /v1/users/{globalUserId}/profile`

**Description:** Get a user profile by global user ID. Forwards to upstream `GET /api/marketing/user-profile/{globalUserId}`.

**Authentication:** Not required

**Path Parameters:**
- `globalUserId` - The global user ID

**Example Request:**
```
GET /v1/users/u-42/profile
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### 42. Get User Report

**Endpoint:** `GET /v1/users/{author}/report`

**Description:** Get a user report by author. Forwards to upstream `GET /api/marketing/user-report/{author}`. **NOT cached** because the upstream persists a categorisation row as a side-effect.

**Authentication:** Not required

**Path Parameters:**
- `author` - The author identifier

**Example Request:**
```
GET /v1/users/alice/report
```

**Cache:** Not cached (upstream has side-effects)

**Status Code:** `200 OK` (upstream status preserved)

---

### 43. List Users

**Endpoint:** `GET /v1/users`

**Description:** List users with optional filters. Forwards to upstream `GET /api/marketing/users`.

**Authentication:** Not required

**Query Parameters (all optional):**
- `audienceClassification` - e.g., `GenZ`
- `influenceTier` - e.g., `TIER_1`
- `postingStyle`
- `dominantTone`
- `primaryPlatform` - e.g., `TWITTER`

**Example Request:**
```
GET /v1/users?audienceClassification=GenZ&influenceTier=TIER_1&primaryPlatform=TWITTER
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### 44. Get User Categories

**Endpoint:** `GET /v1/users/categories`

**Description:** List user categories. Forwards to upstream `GET /api/marketing/users/categories`.

**Authentication:** Not required

**Example Request:**
```
GET /v1/users/categories
```

**Cache:** 5-minute TTL (longer than the default 60 s)

**Status Code:** `200 OK` (upstream status preserved)

---

### 45. Trigger User Sync

**Endpoint:** `POST /v1/users/sync`

**Description:** Trigger a full upstream user sync. Long-running; the wrapper uses an extended 10-minute read timeout for this endpoint. Forwards to upstream `POST /api/marketing/users/sync`.

**Authentication:** Not required

**Example Request:**
```
POST /v1/users/sync
```

**Cache:** Not cached

**Status Code:** `200 OK` (upstream status preserved)

---

### 46. Get Potential Viewers for a Genre

**Endpoint:** `GET /v1/genres/{genre}/potential-viewers`

**Description:** Get potential viewers for a genre. Forwards to upstream `GET /api/marketing/genre/{genre}/potential-viewers`.

**Authentication:** Not required

**Path Parameters:**
- `genre` - The genre name (e.g., `thriller`)

**Example Request:**
```
GET /v1/genres/thriller/potential-viewers
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### 47. Get Super Spreaders for a Genre

**Endpoint:** `GET /v1/genres/{genre}/super-spreaders`

**Description:** Get super spreaders for a genre. Forwards to upstream `GET /api/marketing/genre/{genre}/super-spreaders`.

**Authentication:** Not required

**Path Parameters:**
- `genre` - The genre name (e.g., `sci-fi`)

**Example Request:**
```
GET /v1/genres/sci-fi/super-spreaders
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### 48. Get Channel Strategy for a Genre

**Endpoint:** `GET /v1/genres/{genre}/channel-strategy`

**Description:** Get the channel strategy for a genre. Forwards to upstream `GET /api/marketing/genre/{genre}/channel-strategy`.

**Authentication:** Not required

**Path Parameters:**
- `genre` - The genre name (e.g., `horror`)

**Example Request:**
```
GET /v1/genres/horror/channel-strategy
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### 49. List Targets

**Endpoint:** `GET /v1/targets`

**Description:** List targets with optional filters. Forwards to upstream `GET /v1/targets` (same path, not under `/api/marketing`).

**Authentication:** Not required

**Query Parameters:**
- `genre` (optional) - Genre filter
- `minInfluenceScore` (optional) - Minimum influence score; defaults to `0.0`
- `platform` (optional) - Platform filter (e.g., `TIKTOK`)

**Example Request:**
```
GET /v1/targets?genre=drama&minInfluenceScore=12.5&platform=TIKTOK
```

**Cache:** 60-second TTL

**Status Code:** `200 OK` (upstream status preserved)

---

### 50. Diagnostic: Raw Author Mapping

**Endpoint:** `GET /v1/diagnostics/raw-mapping/{author}`

**Description:** Get raw author mapping diagnostic info. Forwards to upstream `GET /api/test/raw-mapping/{author}`.

**Authentication:** Not required

**Path Parameters:**
- `author` - The author identifier

**Example Request:**
```
GET /v1/diagnostics/raw-mapping/alice
```

**Cache:** Not cached

**Status Code:** `200 OK` (upstream status preserved)

---

### 51. Diagnostic: Temporal Audit

**Endpoint:** `GET /v1/diagnostics/temporal-audit/{author}`

**Description:** Get temporal audit diagnostic info for an author. Forwards to upstream `GET /api/test/temporal-audit/{author}`.

**Authentication:** Not required

**Path Parameters:**
- `author` - The author identifier

**Example Request:**
```
GET /v1/diagnostics/temporal-audit/alice
```

**Cache:** Not cached

**Status Code:** `200 OK` (upstream status preserved)

---

### 52. Diagnostic: Process User

**Endpoint:** `GET /v1/diagnostics/process-user/{author}`

**Description:** Trigger upstream user processing. Forwards to upstream `GET /test/process-user/{author}` — **note:** the upstream path is `/test/...`, NOT `/api/test/...`.

**Authentication:** Not required

**Path Parameters:**
- `author` - The author identifier

**Example Request:**
```
GET /v1/diagnostics/process-user/alice
```

**Cache:** Not cached

**Status Code:** `200 OK` (upstream status preserved)

---

### 52a. List Celebrity Analytics

**Endpoint:** `GET /v1/analytics/celebrity`

**Description:** List managed entities of type `CELEBRITY`. Forwards to upstream `GET /api/analytics/celebrity` and returns the body verbatim on `2xx`.

**Authentication:** Not required

**Example Request:**
```
GET /v1/analytics/celebrity
```

**Cache:** 5-minute TTL (list endpoint)

**Status Code:** `200 OK` (upstream status preserved)

---

### 52b. Get Celebrity Analytics

**Endpoint:** `GET /v1/analytics/celebrity/{entityId}`

**Description:** Full analytics for a single `CELEBRITY` entity. Forwards to upstream `GET /api/analytics/celebrity/{entityId}`. `entityId` is an opaque `managed_entities` id — treated as a string (not assumed numeric) and forwarded verbatim after URL-encoding.

**Authentication:** Not required

**Path Parameters:**
- `entityId` - The managed entity id (must not be blank)

**Example Request:**
```
GET /v1/analytics/celebrity/42
```

**Cache:** 60-second TTL

**Status Codes:**
- `200 OK` (upstream status preserved) — analytics for the entity.
- `400 Bad Request` — `entityId` is empty/blank (rejected before any upstream call).
- `404 Not Found` — relayed unchanged from upstream when `entityId` is unknown or refers to an entity that is not a `CELEBRITY`.
- `502 Bad Gateway` — upstream `5xx`, mapped to a sanitized envelope so SQL/stack fragments are never leaked (same convention as the other AuraMath proxies).

---

## AuraMath Marketing Proxy (`/v1/marketing/**`)

A second proxy surface that mirrors the upstream `/api/marketing/{genre,party,celebrity}` resource tree one-for-one. Twelve GET endpoints plus a `/v1/marketing/_catalog` discovery route forward each upstream path verbatim (URL-encoding `{genre}`, `{party}`, and `{celebrity}` segments so spaces, ampersands, and non-ASCII names like `A R Rahman` or `திமுக` pass through correctly). Each request uses a 15-second connect/read budget (`auramath.marketing-timeout-ms`); successful responses are cached in-memory by full URL for 60 seconds, except the three list endpoints (`/genre`, `/party`, `/celebrity`) which use a 5-minute TTL (`auramath.cache.list-ttl-seconds`). 2xx bodies — including empty arrays like `{"totalVoters":0,"voters":[]}` — are returned to the caller byte-for-byte. Upstream 5xx responses are logged with their `message`/`path` and translated to a sanitized `502 { "error":"upstream_failure", "upstream_path":"…" }` so SQL fragments are never leaked to clients; connection failures/timeouts continue to map to `504 { "error":"upstream_unavailable" }`. Wrapped routes: `GET /v1/marketing/genre` (and `/{genre}/{potential-viewers,super-spreaders,channel-strategy}`); `GET /v1/marketing/party` (and `/{party}/{potential-voters,super-spreaders,channel-strategy}`); `GET /v1/marketing/celebrity` (and `/{celebrity}/{potential-fans,super-fans,channel-strategy}`); `GET /v1/marketing/_catalog` returns the full list with its upstream mapping. Integration tests live in `AuraMathMarketingProxyControllerTest` (path-encoding pass-through for ASCII, spaces, ampersands, and Tamil script; upstream-500 → sanitized-502 mapping; cache hit; `_catalog` shape).

### Entity intelligence reports

Two additional GET wrappers expose the upstream "entity intelligence report" payload — `GET /v1/marketing/entity-report/{entityId}` (shareable, prospect-facing → upstream `GET /api/marketing/entity-report/{entityId}`) and `GET /v1/marketing/entity/{entityId}/report` (in-app, logged-in view → upstream `GET /api/marketing/entity/{entityId}/report`). Both upstream routes return byte-identical JSON; the `{entityId}` is an opaque `managed_entities` id (treated as a string, **not** assumed numeric, and forwarded verbatim after URL-encoding). A blank id is rejected with `400` before any upstream call. These wrappers apply a report-specific status contract instead of the generic pass-through above, and are **not cached** (each report reflects live scoring):

- Upstream `200` **full report** → `200`, body forwarded unchanged.
- Upstream `200` carrying a top-level `message` of `"No entity found for this id"` → translated to **`404`**, the upstream body (with its message) preserved.
- Upstream `200` with the `"No scored post history found for this entity …"` message → **`200`** pass-through (a valid empty result, not an error).
- Upstream `5xx` / connection failure / timeout (or any other unexpected status) → **`502`** with a small envelope `{ "error":"…", "entityId":"…", "upstreamStatus":<code or null> }` (`upstreamStatus` is the upstream code for a 5xx, or `null` for a connection failure/timeout). Upstream error bodies are logged but never leaked to the caller.

Both routes also appear in `/v1/marketing/_catalog`. Integration tests live in `AuraMathEntityReportProxyControllerTest` (full report, 404 translation, no-history pass-through, upstream-500 → 502 envelope, connection-refused → 502 with null `upstreamStatus`, verbatim/encoded entityId, blank-id 400).

### Language-affinity audiences, brand evangelists, and narrative novelty

Five more routes on the same marketing proxy surface (same non-5xx-passthrough / 5xx-sanitized contract, same TTL cache):

- `GET /v1/marketing/language/{language}/users` → upstream `GET /api/marketing/language/{language}/users`. Users with an affinity for a language's movies. 60s TTL.
- `GET /v1/marketing/language/{language}/movie/{movieName}/users` → upstream `GET /api/marketing/language/{language}/movie/{movieName}/users`. Same join, scoped to one movie. Both path segments are URL-encoded. 60s TTL.
- `GET /v1/marketing/brand-evangelists/{keyword}` → upstream `GET /api/marketing/brand-evangelists/{keyword}`. Categorised "Brand Evangelist" authors who have also posted about `{keyword}`. 60s TTL.
- `POST /v1/marketing/narrative-novelty/score` → upstream `POST /api/marketing/narrative-novelty/score`. Scores an arbitrary synopsis (including titles not yet in the database). Request body forwarded verbatim (Jackson `JsonNode`, no server-side validation — upstream's `400 {"error":"synopsis is required"}` is relayed unchanged). Routed through the long-timeout sync client since scoring involves a local embedding-model call. **Not cached.**
- `GET /v1/marketing/narrative-novelty/lookup?movieName={name}` → upstream `GET /api/marketing/narrative-novelty/lookup?movieName={name}`. Scores a title already in `movies_data_collection`. `movieName` is URL-encoded as a query value (space → `+`). 60s TTL.

All five appear in `/v1/marketing/_catalog` (`totalRoutes: 19`).

---

## AuraMath Admin Proxy (`/v1/admin/**`)

Thin POST-only proxy over the upstream **AuraMath** `/api/admin/**` recompute triggers. Every route is routed through the long-timeout sync client (`auramath.sync-read-timeout-ms`, 10 min) and **never cached** — these are synchronous, long-running rebuilds upstream, meant to be called from a job runner or admin tool rather than request-path code. Bodies are ignored on both sides; the upstream response (JSON summary, or a bare `"done"` / `"inserted=<n>"` plain-text body) is forwarded verbatim on success, with the shared `{upstreamStatus, upstreamBody}` envelope on any non-2xx (same convention as the base [AuraMath Proxy](#auramath-proxy-v1-healthz)).

| Wrapper | Upstream | Recomputes |
| --- | --- | --- |
| `POST /v1/admin/run-enrichment` | `POST /api/admin/run-enrichment` | `marketing_target_profiles` (Hawkes α, MOI, tribes, genres). |
| `POST /v1/admin/run-engagement-rating` | `POST /api/admin/run-engagement-rating` | Corpus-relative `engagement_score_raw`/`engagement_rating`. |
| `POST /v1/admin/run-graph-population` | `POST /api/admin/run-graph-population` | AuraMath's `graph_nodes`/`graph_edges` (MOVIE/USER, POSTED_ABOUT/RETWEETED). |
| `POST /v1/admin/resolve-identities` | `POST /api/admin/resolve-identities` | `user_identity_link` from every distinct author across source tables. |
| `POST /v1/admin/recompute-narrative-novelty` | `POST /api/admin/recompute-narrative-novelty` | Synopsis embedding corpus; persists `narrative_novelty_score_v2`/`_raw_v2`. |
| `POST /v1/admin/recompute-narrative-novelty-v1` | `POST /api/admin/recompute-narrative-novelty-v1` | Same algorithm, persists into the legacy `narrative_novelty_score` column. |
| `POST /v1/admin/recompute-conflict-balance` | `POST /api/admin/recompute-conflict-balance` | Corpus-relative `conflict_balance_score` from per-sentence sentiment balance. |

Integration tests live in `AuraMathAdminProxyControllerTest`.

---

## AuraMath Graph Proxy (`/v1/graph/**`)

`GET /v1/graph/users?language={language}&movie={movieName}` forwards to upstream `GET /api/graph/users`, a filterable read API over **AuraMath's own** precomputed graph tables (populated by its `GraphPopulationService`, refreshed via [`POST /v1/admin/run-graph-population`](#auramath-admin-proxy-v1admin)). `language` is required (missing → `400` before any upstream call, same as other required-query-param wrappers); `movie` is optional. Both are URL-encoded as query values. Returns the `{nodes, edges, summary}` shape directly, cached for 60s (`auramath.cache.default-ttl-seconds`); a `language` matching zero MOVIE nodes upstream returns a real `404` relayed unchanged.

**This is distinct from AuraService's own native `GET /api/graph/movies/{movieId}` subgraph endpoint** (`GraphController`), which reads AuraService's own `graph_nodes`/`graph_edges` tables populated by `GraphSyncService` — the two graphs are separate data models under separate path prefixes (`/v1/graph/**` proxy vs. `/api/graph/**` native).

Integration tests live in `AuraMathGraphProxyControllerTest`.

---

## AuraMath Ask Engine Proxy (`/v1/ask/**`)

Thin proxy over the upstream **AuraMath** Ask engine (`/api/ask/**` — natural-language question answering against a target database, read-only, see AuraMath's own docs for the full pipeline). Request/response bodies — including any per-request target-DB `connection`/`password` in `POST /v1/ask` and `POST /v1/ask/test-connection` — are forwarded verbatim over the existing outbound WebClient; AuraService never inspects, logs, or persists them. Unlike the base proxy's blanket `{upstreamStatus, upstreamBody}` wrapping, this surface uses the marketing-style contract: upstream's rich 4xx bodies (clarification, validation, unsafe-SQL) are relayed **unmodified** so callers see the exact `clarificationNeeded`/`requestId`/`error` fields upstream returns; only `5xx` (including a `503` "engine disabled") is sanitized to a generic `502`, consistent with every other proxy surface in this service.

| Wrapper | Upstream | Notes |
| --- | --- | --- |
| `GET /v1/ask/databases` | `GET /api/ask/databases` | Registered target databases (name/driver/host only, no credentials). Cached 5 min (list TTL). |
| `POST /v1/ask/test-connection` | `POST /api/ask/test-connection` | Read-only connectivity probe for a target DB. Not cached. |
| `POST /v1/ask` | `POST /api/ask` | Answer a question against the registry or an explicit target. Not cached; routed through the long-timeout sync client (LLM latency). |
| `GET /v1/ask/admin/metrics` | `GET /api/ask/admin/metrics` | In-memory operational counters. Never cached (live, monotonic counts). |

Integration tests live in `AuraMathAskProxyControllerTest`.

---

## Dev APIs

Development-only endpoints, available when the application is **not** running with the `prod` profile. These are excluded in production via `@Profile("!prod")`.

### 53. Reset Demo

**Endpoint:** `POST /api/dev/reset-demo`

**Description:** Reset the demo environment by backdating all `user_entity_views` rows so that the `whats-changed` and `whats-new` dashboard endpoints have fresh deltas to display. Useful for demo walkthroughs and integration testing.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Response:**
```json
{
  "reset": true,
  "rows_updated": 4,
  "last_seen_at": "2026-01-01T00:00:00Z"
}
```

**Response fields:**
- `reset` — always `true` on success.
- `rows_updated` — number of `user_entity_views` rows modified.
- `last_seen_at` — the timestamp all rows were set to.

**Status Code:** `200 OK`

**Note:** This endpoint is not available when `SPRING_PROFILES_ACTIVE=prod`.

---

## Admin APIs

Admin-only endpoints, mounted under `/api/admin/**`. Access requires the caller to hold
`ROLE_ADMIN` — enforced both by Spring Security (`/api/admin/**` request matcher) and by
`@PreAuthorize` on the handler. A non-admin (or unauthenticated) caller receives `403 Forbidden`.

### List Users

**Endpoint:** `GET /api/admin/users`

**Description:** Return all users as a minimal `id` + `username` projection, sorted by username
(case-insensitive). Intended to populate the admin user-selector dropdown that drives the `ownerId`
view-scoping parameter. The password hash, role, and other account fields are never included.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Example Request:**
```
GET /api/admin/users
```

**Response:**
```json
[
  {
    "id": 2,
    "username": "admin"
  },
  {
    "id": 1,
    "username": "user"
  }
]
```

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).

---

### Issue / Assign License

**Endpoint:** `POST /api/admin/licenses`

**Description:** Issue a new active license to a user at a given tier, generating a unique license
key. The user operates under a **single** active license, so any license they already held is
deactivated first. The response carries only the generated key — **no price**.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "userId": 1,
  "tier": "GOLD",
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

- `userId` — required; the user to assign the license to.
- `tier` — required; one of `BRONZE`, `SILVER`, `GOLD`, `DIAMOND`.
- `expiresAt` — optional ISO-8601 instant; omit for a license that never expires.

**Response:**
```json
{
  "licenseKey": "AURA-3f8c2b1a-9d44-4e1f-8a7c-1b2c3d4e5f60"
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).
- `404 Not Found` — no user exists with the given `userId`.
- `400 Bad Request` / `Validation Failed` — `userId` or `tier` missing.

---

### List Licenses

**Endpoint:** `GET /api/admin/licenses`

**Description:** Return every license in the system as an admin summary. Carries the tier but
**never any price**.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Response:**
```json
[
  {
    "id": 5,
    "licenseKey": "AURA-3f8c2b1a-9d44-4e1f-8a7c-1b2c3d4e5f60",
    "tier": "GOLD",
    "userId": 1,
    "username": "user",
    "active": true,
    "issuedAt": "2026-06-14T04:53:00Z",
    "expiresAt": null
  }
]
```

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).

---

### Update License

**Endpoint:** `PATCH /api/admin/licenses/{id}`

**Description:** Partially update a license: change its `tier`, its `active` flag, or both. A field
omitted (or `null`) is left unchanged. Returns the updated summary (**no price**).

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `id` — License ID (e.g., 5)

**Request Body:**
```json
{
  "tier": "DIAMOND",
  "active": true
}
```

**Response:** The updated license, in the same shape as a [List Licenses](#list-licenses) entry.

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).
- `404 Not Found` — no license exists with the given `id`.

---

### List License Prices

**Endpoint:** `GET /api/admin/license-prices`

**Description:** The price catalog for every license tier. **This is the only endpoint in the API
that returns price data** — it is admin-only and price information never appears on any user-facing
license, usage, or limit response. The catalog is seeded at startup with a row (price `0`) for every
tier.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Response:**
```json
[
  {
    "tier": "BRONZE",
    "price": 0.00,
    "currency": "USD",
    "updatedAt": "2026-06-14T04:53:00Z"
  },
  {
    "tier": "DIAMOND",
    "price": 499.00,
    "currency": "USD",
    "updatedAt": "2026-06-14T04:53:00Z"
  }
]
```

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).

---

### Update License Prices

**Endpoint:** `PUT /api/admin/license-prices`

**Description:** Upsert one or more tier prices and return the full catalog. Admin-only.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
Content-Type: application/json
```

**Request Body:** a list of price updates.
```json
[
  { "tier": "GOLD", "price": 199.00, "currency": "USD" },
  { "tier": "DIAMOND", "price": 499.00 }
]
```

- `tier` — required; the tier to price.
- `price` — required; the new price.
- `currency` — optional; when omitted the existing currency on the row is preserved.

**Response:** The full price catalog, in the same shape as [List License Prices](#list-license-prices).

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).
- `400 Bad Request` / `Validation Failed` — a list entry is missing `tier` or `price`.

---

### Create Offer Key

**Endpoint:** `POST /api/admin/offer-keys`

**Description:** Create an **offer key** — a redeemable code that grants a temporary tier
[override](#offer-key-overrides-effective-tier) when a user redeems it via
[`POST /api/license/redeem-offer`](#l3-redeem-offer-key). Carries **no price** — an offer key grants
access, not a purchase.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
Content-Type: application/json
```

**Request Body:**
```json
{
  "code": "DIAMOND-LAUNCH-2026",
  "grantsTier": "DIAMOND",
  "active": true,
  "expiresAt": "2026-12-31T23:59:59Z",
  "maxRedemptions": 100
}
```

- `code` — required; the code users type to redeem. Must be unique.
- `grantsTier` — optional; the tier the key grants. Defaults to `DIAMOND` when omitted.
- `active` — optional; whether the key is redeemable. Defaults to `true` when omitted.
- `expiresAt` — optional ISO-8601 instant; omit for a key that never expires. A redeemed override
  inherits this expiry.
- `maxRedemptions` — optional positive integer; omit for unlimited redemptions.

**Response:**
```json
{
  "id": 3,
  "code": "DIAMOND-LAUNCH-2026",
  "grantsTier": "DIAMOND",
  "active": true,
  "expiresAt": "2026-12-31T23:59:59Z",
  "maxRedemptions": 100,
  "redemptionCount": 0
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).
- `400 Bad Request` — `code` is missing/blank, `maxRedemptions` is not positive, or a key with that
  `code` already exists.

---

### List Offer Keys

**Endpoint:** `GET /api/admin/offer-keys`

**Description:** Return every offer key, including its accumulated `redemptionCount`. **No price**.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Response:** a list of offer keys, each in the same shape as a [Create Offer Key](#create-offer-key)
response.

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).

---

### Get Offer Key

**Endpoint:** `GET /api/admin/offer-keys/{id}`

**Description:** Return a single offer key by id. **No price**.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Path Parameters:**
- `id` — Offer key ID (e.g., 3)

**Response:** The offer key, in the same shape as a [Create Offer Key](#create-offer-key) response.

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).
- `404 Not Found` — no offer key exists with the given `id`.

---

### Update Offer Key

**Endpoint:** `PATCH /api/admin/offer-keys/{id}`

**Description:** Partially update an offer key: change its `grantsTier`, `active` flag, `expiresAt`,
and/or `maxRedemptions`. A field omitted (or `null`) is left unchanged. The `code` and the
accumulated `redemptionCount` are immutable here. Returns the updated key (**no price**).

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
Content-Type: application/json
```

**Path Parameters:**
- `id` — Offer key ID (e.g., 3)

**Request Body:**
```json
{
  "active": false,
  "maxRedemptions": 50
}
```

**Response:** The updated offer key, in the same shape as a [Create Offer Key](#create-offer-key)
response.

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).
- `404 Not Found` — no offer key exists with the given `id`.
- `400 Bad Request` — `maxRedemptions` is not positive.

---

### Delete Offer Key

**Endpoint:** `DELETE /api/admin/offer-keys/{id}`

**Description:** Permanently delete an offer key. Overrides already applied to users' licenses are
unaffected.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Path Parameters:**
- `id` — Offer key ID (e.g., 3)

**Status Code:** `204 No Content`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).
- `404 Not Found` — no offer key exists with the given `id`.

---

### Box Office Backtest

Admin-only. Runs the [Box Office Prediction](#27-get-box-office-prediction) 100+3-factor prompt against historical Indian movies in `movies_data_collection` and checks each prediction against the actual gross, to validate how close AuraLLM's predictions land to reality. A run executes in the background (`@Async`); the start/rerun endpoints return immediately with a `runId` to poll. Run state is held in memory only and does not survive an app restart.

#### Start Box Office Backtest Run

**Endpoint:** `POST /api/admin/box-office-backtest`

**Description:** Starts a run over up to `limit` eligible movies (default 50) and returns immediately with the initial run status. Poll [Get Box Office Backtest Run Status](#get-box-office-backtest-run-status) for progress. Keep `limit` small for a first smoke test — each movie is one real call to the AuraLLM gateway.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Query Parameters:**
- `limit` — max number of movies to include in the run. Default `50`. Optional.

**Example Request:**
```
POST /api/admin/box-office-backtest?limit=10
```

**Response:**
```json
{
  "runId": "a1b2c3d4",
  "state": "RUNNING",
  "totalMovies": 10,
  "processedCount": 0,
  "validatedCount": 0,
  "withinPredictedRangeCount": 0,
  "startedAt": "2026-07-26T09:00:00Z",
  "completedAt": null,
  "logFilePath": "/var/log/aura/box-office-backtest-a1b2c3d4.log",
  "errorMessage": null,
  "factorSummary": [],
  "results": []
}
```

**Response fields:**
- `state` — `RUNNING`, `COMPLETED`, or `FAILED`.
- `processedCount` — movies processed so far (success or per-movie error); `validatedCount` — of those, how many had both an actual and predicted gross to compare; `withinPredictedRangeCount` — of the validated ones, how many landed within tolerance.
- `results` — per-movie `BoxOfficeBacktestResult` entries, populated as the run progresses. Each includes `baseline` (the server-computed pre-compounding potential), `compoundMultiplier`, `predictedGrossUsd`, `withinTolerance`, `deviationPct`, `factorDeltas`, and `rationale`; `error` is set (with other fields `null`) when the LLM call or response parsing failed for that movie — a single bad response never aborts the run.
- `factorSummary` — populated only once `state` is `COMPLETED`: per-catalog-factor aggregates (how often the LLM rated it vs. answered "NA", and its average delta when rated) — see [Get Box Office Backtest Run Status](#get-box-office-backtest-run-status) for a populated example.

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).

---

#### Get Box Office Backtest Run Status

**Endpoint:** `GET /api/admin/box-office-backtest/{runId}`

**Description:** Poll the live/completed state of a run started by [Start Box Office Backtest Run](#start-box-office-backtest-run) or one of the rerun endpoints below.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Path Parameters:**
- `runId` — the run id returned when the run was started.

**Example Request:**
```
GET /api/admin/box-office-backtest/a1b2c3d4
```

**Response:**
```json
{
  "runId": "a1b2c3d4",
  "state": "COMPLETED",
  "totalMovies": 10,
  "processedCount": 10,
  "validatedCount": 9,
  "withinPredictedRangeCount": 6,
  "startedAt": "2026-07-26T09:00:00Z",
  "completedAt": "2026-07-26T09:04:12Z",
  "logFilePath": "/var/log/aura/box-office-backtest-a1b2c3d4.log",
  "errorMessage": null,
  "factorSummary": [
    {
      "factorNumber": 12,
      "factorName": "Festive Release Window",
      "ratedCount": 8,
      "naCount": 2,
      "avgDeltaWhenRated": 0.14
    }
  ],
  "results": [
    {
      "movieName": "Movie A",
      "releaseDate": "2025-01-24",
      "actualGrossUsd": 12500000.0,
      "actualGrossSource": "movies_data_collection",
      "baseline": {
        "adjustedBudgetUsd": 6000000.0,
        "rStar": 1.2,
        "rDirector": 1.1,
        "rConcept": 1.05,
        "rIP": 1.0,
        "baselineB0Usd": 8316000.0
      },
      "compoundMultiplier": 1.42,
      "predictedGrossUsd": 11808720.0,
      "withinTolerance": true,
      "deviationPct": -5.53,
      "factorDeltas": { "Festive Release Window": 0.14 },
      "postReleaseFactorsHelp": ["Positive word-of-mouth in week 2"],
      "postReleaseFactorsHurt": [],
      "rationale": "Strong opening aided by the festive window; the prediction lands within tolerance of actual gross.",
      "error": null
    }
  ]
}
```

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).
- `404 Not Found` — no run exists with the given `runId` (including a valid-looking id from before the app last restarted, since run state does not persist).

---

#### Rerun Box Office Backtest

**Endpoint:** `POST /api/admin/box-office-backtest/{runId}/rerun`

**Description:** Re-runs the prompt over exactly the same movies as run `runId` — use this after editing the prompt catalog's impact ranges, to check whether the change actually helped on a like-for-like movie set rather than a fresh (possibly different) sample. Returns a new run (new `runId`) in the same shape as [Start Box Office Backtest Run](#start-box-office-backtest-run).

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Path Parameters:**
- `runId` — the prior run whose movie set should be replayed.

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).
- `404 Not Found` — no run exists with the given `runId`.

---

#### Rerun Box Office Backtest (Explicit Movie Set)

**Endpoint:** `POST /api/admin/box-office-backtest/rerun-movies`

**Description:** Same as [Rerun Box Office Backtest](#rerun-box-office-backtest), but takes the movie set explicitly instead of referencing a prior run — for validating a prompt-catalog change against a specific movie list captured before the app restarted to load that change (run state is in-memory only and doesn't survive a restart).

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Request Body:**
```json
[
  { "movieName": "Movie A", "releaseDate": "2025-01-24" },
  { "movieName": "Movie B", "releaseDate": "2025-03-07" }
]
```

**Request fields:**
- Each entry is a movie's natural key in `movies_data_collection` (that table has no surrogate id): `movieName` and `releaseDate`.

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).

---

## Audit Log APIs

### List Audit Logs

**Endpoint:** `GET /api/audit-logs`

**Description:** Admin-only read access to the API audit trail — one row per request handled by the service, recording who called what, when, whether it succeeded, and request details. Every filter is optional; the bare endpoint returns the full trail newest-first and the query parameters narrow it. Access requires `ROLE_ADMIN`, enforced by the `/api/audit-logs/**` request matcher.

**Headers:**
```
Authorization: Bearer {admin_jwt_token}
```

**Query Parameters:**
- `username` — exact match on the authenticated principal that made the call (`"anonymous"` for unauthenticated requests). Optional.
- `success` — `true`/`false`; filters to 2xx/3xx responses or non-2xx/3xx responses respectively. Optional.
- `from` / `to` — ISO-8601 timestamp bounds (inclusive) on when the request completed, e.g. `2026-07-01T00:00:00Z`. Optional.
- `page` — zero-based page number. Default `0`. Optional.
- `size` — page size. Default `50`, hard-capped at `200`. Optional.

**Example Request:**
```
GET /api/audit-logs?username=alice&success=false&from=2026-07-01T00:00:00Z&page=0&size=50
```

**Response:**
```json
{
  "content": [
    {
      "id": 91234,
      "timestamp": "2026-07-26T09:03:11Z",
      "username": "alice",
      "httpMethod": "DELETE",
      "path": "/api/mentions/9123",
      "queryString": null,
      "statusCode": 404,
      "success": false,
      "durationMs": 12,
      "clientIp": "203.0.113.7",
      "userAgent": "Mozilla/5.0 ...",
      "requestBody": null
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 50
}
```

**Response fields:**
- Standard Spring `Page` envelope wrapping `AuditLog` rows.
- `requestBody` — truncated request payload for write operations; redacted (`null`) for authentication endpoints so credentials are never persisted.
- `clientIp` — honours `X-Forwarded-For` when present.
- `success` — convenience flag: `true` for 2xx/3xx responses, `false` otherwise.

**Status Code:** `200 OK`

**Error Responses:**
- `403 Forbidden` — the caller is not an admin (or no/invalid JWT).

---

## Error Responses

All endpoints may return the following error responses:

### 400 Bad Request

**Example:**
```json
{
  "timestamp": "2025-11-08T11:30:00.000+00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Entity not found with id: 999"
}
```

### 401 Unauthorized

**Example:**
```json
{
  "timestamp": "2025-11-08T11:30:00.000+00:00",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid username or password"
}
```

### 403 Forbidden

Returned when an authenticated user attempts an action reserved for administrators — e.g. a
non-admin passing the `ownerId` query parameter, or any caller hitting an `/api/admin/**` endpoint
without `ROLE_ADMIN`.

**Example:**
```json
{
  "timestamp": "2025-11-08T11:30:00.000+00:00",
  "status": 403,
  "error": "Forbidden",
  "message": "Only administrators may scope by ownerId"
}
```

Tier-gated **premium features** no longer return `403` at all: a caller below the required tier gets a
`200 OK` `EntitledResponse` with `entitled=false` and a masked `preview` instead (see
[Premium Feature Tier Gating](#premium-feature-tier-gating)).

### 409 Conflict

Returned when an operation would breach a per-tier license cap (entity or keyword — see
[Licensing & Usage APIs](#licensing--usage-apis)). The body is intentionally minimal and
**price-free**, carrying only the limit type and the relevant counts:

**Example:**
```json
{
  "limitType": "KEYWORDS",
  "limit": 15,
  "current": 17
}
```

(Some other endpoints, e.g. manual sentiment-alert creation, also use `409 Conflict` for duplicates,
with an empty body.)

### 422 Validation Error

**Example:**
```json
{
  "timestamp": "2025-11-08T11:30:00.000+00:00",
  "status": 400,
  "error": "Validation Failed",
  "errors": {
    "username": "Username is required",
    "password": "Password is required"
  }
}
```

### 500 Internal Server Error

**Example:**
```json
{
  "timestamp": "2025-11-08T11:30:00.000+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred"
}
```

---

## Sample Data

The application comes pre-loaded with sample data:

### Entities
- **Movie:** "The Quantum Paradox" (ID: 1)
  - Director: Christopher Nolan
  - Actors: Leonardo DiCaprio, Emma Stone, Tom Hardy
  - Competitors: Inception 2, Interstellar Reloaded

- **Celebrity:** "Emma Stone" (ID: 2)

- **Movie:** "Inception 2" (ID: 3)
  - Director: Denis Villeneuve
  - Actors: Ryan Gosling, Margot Robbie

- **Movie:** "Interstellar Reloaded" (ID: 4)
  - Director: James Cameron
  - Actors: Zendaya, Timothée Chalamet

### Mentions
- 50 mentions per entity (200 total)
- Distributed across all platforms (X, Reddit, YouTube, Instagram)
- Various sentiments (Positive, Negative, Neutral)
- Dates spanning the last 90 days
- Various locations and author demographics

---

## Testing the API

### Using cURL

1. **Login to get JWT token:**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}'
```

2. **Use the token for authenticated requests:**
```bash
curl -X GET http://localhost:8080/api/entities/movie \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

3. **Get entity statistics:**
```bash
curl -X GET http://localhost:8080/api/dashboard/1/stats \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"
```

### Using Postman

1. Import the endpoints into Postman
2. Create an environment variable for `jwt_token`
3. Login using `/api/auth/login` and save the token
4. Use `{{jwt_token}}` in the Authorization header for other requests

---

## Architecture

### Package Structure
```
com.aura.service
├── config          # Security and application configuration
├── controller      # REST API controllers
├── dto             # Data Transfer Objects
├── entity          # JPA entities
├── enums           # Enumerations
├── exception       # Global exception handling
├── repository      # JPA repositories
├── security        # JWT and authentication
└── service         # Business logic
```

### Key Components

- **SecurityConfig:** Spring Security configuration with JWT
- **JwtService:** JWT token generation and validation
-
- **DataInitializer:** Pre-loads sample data on startup
- **MorningDigestService:** Scheduled per-user overnight digest at 8 AM local time via `EmailChannel`
- **MarketingAggregationService:** Aggregates marketing data (top spreaders, viral seeds, aspect drivers, brand evangelists, genre data) across multiple keywords filtered by language, industry, genre, state, or entity ID
- **GlobalExceptionHandler:** Centralized error handling
- **Mock Services:** LLM, Social Media, and Analytics mock implementations

### Standalone Python Batch Jobs

`python-batch-jobs/` (repo root) holds scheduled offline analyses that connect directly to the
shared `aura` Postgres DB and are **not** part of this Java build — see
`docs/AUDIENCE_BEHAVIOR_PATTERN_FEATURE_BREAKDOWN.md` for the full design (features F4, F5, F7).
So far only **F7**, `playbook_pattern_miner.py`, is built: it mines ordered
`CheckpointType`/`SPILLOVER_<platform>`/`SENTIMENT_SPIKE` symbol sequences per tracked movie and
finds which subsequences are statistically associated (Fisher's exact + BH-FDR, `q < 0.10`) with
landing in the top vs. bottom tertile of a movie's `(industry, language)` cohort by cumulative
engagement volume, persisting surviving patterns to `playbook_patterns`. See
`python-batch-jobs/README.md` for setup, run, and scheduling instructions.

---

## Production Deployment

### Environment Variables

Set the following environment variables for production:

```bash
export JWT_SECRET=your-secure-secret-key-here
export SPRING_PROFILES_ACTIVE=prod
```

### Database Configuration

For production, replace H2 with a persistent database (PostgreSQL, MySQL, etc.) by updating `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/auradb
spring.datasource.username=your_username
spring.datasource.password=your_password
spring.jpa.hibernate.ddl-auto=update
```

### Building for Production

```bash
mvn clean package
java -jar target/aura-service-1.0.0.jar
```

## License

This project is licensed under the MIT License.

---

## AuraMath Proxy (`/v1/**`, `/healthz`)

AuraService also exposes a thin REST proxy in front of the upstream **AuraMath** service. Each wrapper endpoint forwards path/query/body verbatim to the corresponding upstream route, preserves upstream HTTP status codes, and applies a 60-second in-memory TTL cache to read-only endpoints (5 minutes for `/v1/users/categories`).

### Configuration

| Env var / property | Default | Description |
| --- | --- | --- |
| `AURAMATH_BASE_URL` / `auramath.base-url` | `http://localhost:8081` | Upstream AuraMath base URL. |
| `auramath.connect-timeout-ms` | `30000` | Connection timeout for the pooled WebClient. |
| `auramath.read-timeout-ms` | `60000` | Default response/read timeout. |
| `auramath.sync-read-timeout-ms` | `600000` | Read timeout used only for `POST /v1/users/sync` (10 min). |
| `auramath.cache.default-ttl-seconds` | `60` | TTL for cacheable GET endpoints. |
| `auramath.cache.categories-ttl-seconds` | `300` | TTL for `/v1/users/categories`. |
| `auramath.cache.max-entries` | `1000` | Max entries in the in-memory cache. |

Wrapper paths (`/v1/**`, `/healthz`, `/openapi.yaml`, `/v3/api-docs/**`, `/swagger-ui/**`) are permitted without JWT authentication — the upstream is responsible for its own auth.

### Run

```bash
export AURAMATH_BASE_URL=http://localhost:8081
mvn spring-boot:run
```

OpenAPI spec is served three ways:
- Static YAML: `http://localhost:8080/openapi.yaml`
- Springdoc JSON: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

### Liveness/readiness

```bash
curl -s http://localhost:8080/healthz
# {"status":"UP","upstream":"reachable"}
```

### Error envelopes

- Connection error / timeout → HTTP `504` with body:
  ```json
  { "error": "upstream_unavailable", "endpoint": "/v1/<path>" }
  ```
- Upstream non-2xx → original status code with body:
  ```json
  { "upstreamStatus": <code>, "upstreamBody": <original_body_or_text> }
  ```

### Endpoint reference

The wrapper endpoints are documented in detail as APIs #37–#52b in the [AuraMath Proxy APIs](#auramath-proxy-apis) section above, plus two unnumbered additions inserted alongside their siblings: **Aspect Drivers by Entity** (`GET /v1/aspect-drivers?entityId={id}`) and the **Lookalike Ranking Diagnostic** (`GET /v1/find-lookalikes/diff`).

### Tests

Proxy tests live in `src/test/java/com/aura/service/proxy/` and run against a stubbed upstream via `okhttp3.mockwebserver`:

- `AuraMathProxyControllerTest` — one happy-path test per wrapper endpoint plus `/healthz`, cache-hit, non-2xx envelope, and the missing-`seedAuthorId` → 400 (without upstream call) test.
- `AuraMathProxyUnavailableTest` — points the WebClient at a closed port and asserts `504` with the `{error: upstream_unavailable}` envelope.

```bash
mvn test -Dtest='AuraMathProxy*'
```

---

## Support

For issues or questions, please contact the development team
