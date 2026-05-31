# AuraService - Online Reputation Management System

A complete Java Spring Boot backend application for managing online reputation for celebrities and movies.

## Technology Stack

- **Java Version:** 17
- **Framework:** Spring Boot 3.2.0
- **Database:** H2 (in-memory)
- **Authentication:** Spring Security 6 with JWT
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
    director VARCHAR(255)
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
```

## API Documentation

All endpoints except `/api/auth/*` require JWT authentication. Include the JWT token in the `Authorization` header as `Bearer {token}`.

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

**Description:** Create a new managed entity (celebrity or movie)

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
  "keywords": ["keanureeves", "matrix", "sequel"]
}
```

**Response:**
```json
{
  "id": 5,
  "name": "The Matrix Resurrections",
  "type": "MOVIE",
  "director": "Lana Wachowski",
  "actors": ["Keanu Reeves", "Carrie-Anne Moss", "Yahya Abdul-Mateen II"],
  "keywords": ["keanureeves", "matrix", "sequel"],
  "competitors": []
}
```

**Status Code:** `200 OK`

---

### 4. Get All Entities

**Endpoint:** `GET /api/entities/{entityType}`

**Description:** Retrieve a list of all managed entities of a specific type

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityType` - The type of the entity (e.g., `movie`, `celebrity`)

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

### 5. Get Entity by ID

**Endpoint:** `GET /api/entities/{entityType}/{id}`

**Description:** Retrieve detailed information about a specific entity

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
  ]
}
```

**Status Code:** `200 OK`

---

### 6. Update Competitors

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

---

### 7. Update Keywords

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

---

### 8. Delete Entity

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
- `400 Bad Request` - Entity not found, or the entity is not of the given `entityType`.

---

## Checkpoint Management APIs

Checkpoints mark significant dates for a managed entity (e.g., trailer release, opening weekend, award nomination). They are referenced by the sentiment-over-time, checkpoint-impact, and checkpoint-trend dashboard APIs to overlay milestones on sentiment charts.

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
  "description": "Trailer Launch"
}
```

**Validation:**
- `entityId` — required.
- `checkpointDate` — required (ISO-8601 date).
- `description` — required, non-blank, max 20 characters.

**Response:**
```json
{
  "id": 10,
  "entityId": 1,
  "entityName": "The Quantum Paradox",
  "checkpointDate": "2026-03-15",
  "description": "Trailer Launch"
}
```

**Status Code:** `201 Created`

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

**Response:**
```json
[
  {
    "id": 10,
    "entityId": 1,
    "entityName": "The Quantum Paradox",
    "checkpointDate": "2026-03-15",
    "description": "Trailer Launch"
  },
  {
    "id": 11,
    "entityId": 1,
    "entityName": "The Quantum Paradox",
    "checkpointDate": "2026-04-01",
    "description": "Opening Weekend"
  }
]
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
  "description": "Trailer v2"
}
```

**Field Rules:**
- `checkpointDate` — optional (ISO-8601 date). When provided, must not collide with another checkpoint for the same entity (entity + date is unique).
- `description` — optional; when provided, must be non-blank and at most 20 characters.

**Response:**
```json
{
  "id": 10,
  "entityId": 1,
  "entityName": "The Quantum Paradox",
  "checkpointDate": "2026-03-20",
  "description": "Trailer v2"
}
```

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

**Response:** No body.

**Status Code:** `204 No Content`

---

## Dashboard APIs

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

Each mention carries the same `available_actions` / `action_history_summary` fields as the single-entity endpoint above — see section 15 for the field semantics.

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
- `404 Not Found` — No mention with the given id

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

### 21. Generate Crisis Plan

**Endpoint:** `POST /api/crisis/generate-plan`

**Description:** Generate a detailed crisis management plan (Mock LLM)

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

**Response:**
```json
{
  "generatedPlan": "Mock Crisis Management Plan:\n\n1. Immediate Response: Issue a public statement acknowledging the situation.\n2. Assessment: Gather all facts and assess the severity of the crisis.\n3. Communication Strategy: Develop key messages for different stakeholders.\n4. Action Plan: Implement corrective measures and monitor progress.\n5. Follow-up: Continue monitoring sentiment and adjust strategy as needed.\n\nThis is a mock plan. In production, this would be generated by an actual LLM based on: Generate a detailed crisis management plan for The Quantum Paradox (MOVIE) regarding the following crisis: Negative reviews are flooding social media after controversial scene in the movie"
}
```

**Status Code:** `200 OK`

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

Per-mention actions that wrap the LLM and social-media services into auditable, persisted operations. Most endpoints are mounted under `/api/mentions/{mentionId}/actions`; **Report abuse** is a sibling route at `/api/mentions/{mentionId}/report-abuse`. All are JWT-protected — pass `Authorization: Bearer {jwt_token}`.

- **List actions** returns every `ReplyDraft`, `CrisisPlan`, and mobilize call ever recorded for the mention, with the actor's username on every row, sorted newest first. Used by the UI to show "you already drafted a reply 2h ago" so users don't double-act.
- **Draft reply** generates a reply via `LLMService.generateReply` (entity name + mention content + sentiment) and persists a `ReplyDraft` row (`status=DRAFT`). Outer quotes from the LLM output are stripped to match the existing `/api/interact/generate-reply` behavior.
- **Post reply** loads a previously created draft, calls `SocialMediaService.postReply(platform, postId, text)` against the mention's source platform and post id, and flips the draft to `status=POSTED` with `postedAt` set to the server time.
- **Escalate to crisis** generates a crisis-management plan via `LLMService.generateCrisisPlan` using the mention's content as the crisis description, and persists a `CrisisPlan` row attributed to the calling user.
- **Mobilize allies** pulls the entity's keywords, fans out parallel calls to `GET /v1/top-spreaders/{keyword}` (via the existing AuraMath WebClient and `TopSpreaderLookupService`), filters the union of spreaders down to authors whose mention sentiment for this entity is predominantly `POSITIVE`, and returns the top 10 with a per-ally suggested DM template generated via `LLMService`. Responses are cached in-process per `(entityId, mentionId)` for 5 minutes. Every call (including cache hits) persists a `MobilizeAction` row attributed to the calling user so the action log can show prior mobilize attempts.
- **Report abuse** files an abuse complaint against the mention and persists an `AbuseReport` row attributed to the calling user with `status=SUBMITTED`. The `externalRef` is left `null` until the report is forwarded to an external moderation system.

Every action response except **Report abuse** includes a `mention` object shaped like `MentionResponse` so the UI can render the action result without a second fetch; Report abuse returns the persisted `AbuseReport` directly.

### 22. List Mention Actions

**Endpoint:** `GET /api/mentions/{mentionId}/actions`

**Description:** Return every `ReplyDraft`, `CrisisPlan`, and `MobilizeAction` row recorded for the mention, merged into a single timeline sorted by `createdAt` descending (newest first). Each row carries the acting user's username so the UI can show "you already drafted a reply 2h ago" and prevent users from double-acting.

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `mentionId` — Mention ID to fetch the action log for

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
    "sentimentScore": 12
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
    "sentimentScore": 12
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
    "sentimentScore": 12
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
    "sentimentScore": 88
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

**Description:** File an abuse complaint against a mention and persist it as an `AbuseReport` row attributed to the calling user. The report is created with `status=SUBMITTED` and `submittedAt` set to the current server time. `externalRef` stays `null` until the report is forwarded to an external moderation system. Returns the persisted `AbuseReport`.

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
  "externalRef": null,
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
- `externalRef` — reference returned by the external moderation system; `null` until forwarded.
- `submittedAt` — server timestamp when the report was filed.

**Status Codes:**
- `200 OK` — Report filed and persisted.
- `400 Bad Request` — `category` is missing or not a valid value.
- `404 Not Found` — No mention with the given id.

---

## Analytics APIs

### 27. Get Box Office Prediction

**Endpoint:** `GET /api/analytics/{movieId}`

**Description:** Get predicted box office revenue for a movie (Mock analytics)

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

**Note:** Mock implementation returns random values between $50M-$150M

---

## Alerts APIs

Sentiment alerts are produced by `SentimentAlertService`, which runs two background detectors:

- **`SPIKE`** — every 5 minutes, scans each managed entity's rolling 60-minute window (10-mention minimum, 30-minute dedup window). Per-user [alert rules](#alert-rules-apis) drive the threshold: a rule fires when the negative-sentiment ratio rises by at least `threshold` over the 7-day baseline (e.g. `0.10`), and the resulting alert is tagged with the owning user (`ownerUserId`). When no rule applies to an entity, it falls back to the default behavior — fire when the ratio exceeds 1.5x the baseline — and the alert is left un-owned (`ownerUserId: null`).
- **`INFLUENCER_NEGATIVE`** — every 1 minute, picks up newly inserted `NEGATIVE` mentions (id-based watermark, bulk-insert friendly) whose author appears in the top-50 spreader list for any of the managed entity's keywords. Spreader lookups are cached for 10 minutes per keyword. If users have `INFLUENCER_NEGATIVE` [alert rules](#alert-rules-apis) for the entity, one owned alert is raised per such user; otherwise a single un-owned alert is raised.

After an alert is persisted, `AlertDispatcher` fans it out to two async channels (failures are caught and logged — they do not block alert persistence):

- **Email** — `EmailChannel` interface with a log-only `NoopEmailChannel` `@Component` shipped by default (swap in SendGrid in prod). Subject is `[Aura] {entityName} negative spike`; body lists the top 3 most recent negative mentions for the entity with their permalinks.
- **Webhook** — `WebhookChannel` POSTs the alert JSON to every user's configured `alertWebhookUrl` (see [Set Alert Webhook URL](#set-alert-webhook-url)).

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

### 31f. Export Workspace

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

### 31g. Import Workspace

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

**Response format:**
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

**Validation Error (invalid subType):**
```json
{ "error": "subType must be one of: potential-viewers, super-spreaders, channel-strategy" }
```

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

## AuraMath Marketing Proxy (`/v1/marketing/**`)

A second proxy surface that mirrors the upstream `/api/marketing/{genre,party,celebrity}` resource tree one-for-one. Twelve GET endpoints plus a `/v1/marketing/_catalog` discovery route forward each upstream path verbatim (URL-encoding `{genre}`, `{party}`, and `{celebrity}` segments so spaces, ampersands, and non-ASCII names like `A R Rahman` or `திமுக` pass through correctly). Each request uses a 15-second connect/read budget (`auramath.marketing-timeout-ms`); successful responses are cached in-memory by full URL for 60 seconds, except the three list endpoints (`/genre`, `/party`, `/celebrity`) which use a 5-minute TTL (`auramath.cache.list-ttl-seconds`). 2xx bodies — including empty arrays like `{"totalVoters":0,"voters":[]}` — are returned to the caller byte-for-byte. Upstream 5xx responses are logged with their `message`/`path` and translated to a sanitized `502 { "error":"upstream_failure", "upstream_path":"…" }` so SQL fragments are never leaked to clients; connection failures/timeouts continue to map to `504 { "error":"upstream_unavailable" }`. Wrapped routes: `GET /v1/marketing/genre` (and `/{genre}/{potential-viewers,super-spreaders,channel-strategy}`); `GET /v1/marketing/party` (and `/{party}/{potential-voters,super-spreaders,channel-strategy}`); `GET /v1/marketing/celebrity` (and `/{celebrity}/{potential-fans,super-fans,channel-strategy}`); `GET /v1/marketing/_catalog` returns the full list with its upstream mapping. Integration tests live in `AuraMathMarketingProxyControllerTest` (path-encoding pass-through for ASCII, spaces, ampersands, and Tamil script; upstream-500 → sanitized-502 mapping; cache hit; `_catalog` shape).

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

The 16 wrapper endpoints are documented in detail as APIs #37–#52 in the [AuraMath Proxy APIs](#auramath-proxy-apis) section above.

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
