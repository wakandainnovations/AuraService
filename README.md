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
    role VARCHAR(255) NOT NULL
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

### 9. Get Average Entity Statistics for Multiple Entities

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
      ]
    }
  ]
}
```

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

### 15. Get Filtered Mentions

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
      "sentiment": "POSITIVE"
    },
    {
      "id": 2,
      "managedEntityId": 1,
      "platform": "X",
      "postId": "The_Quantum_Paradox_post_5",
      "content": "Incredible performance! Oscar-worthy for sure.",
      "author": "critic_sarah",
      "postDate": "2025-11-03T14:20:00Z",
      "sentiment": "POSITIVE"
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

**Status Code:** `200 OK`

---

### 16. Get Filtered Mentions for a Cluster

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
      "sentiment": "POSITIVE"
    },
    {
      "id": 2,
      "managedEntityId": 1,
      "platform": "X",
      "postId": "The_Quantum_Paradox_post_5",
      "content": "Incredible performance! Oscar-worthy for sure.",
      "author": "critic_sarah",
      "postDate": "2025-11-03T14:20:00Z",
      "sentiment": "POSITIVE"
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

**Status Code:** `200 OK`

---

### 17. Get Hourly Activity Distribution

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

## Interaction APIs

### 17. Generate Reply

**Endpoint:** `POST /api/interact/generate-reply`

**Description:** Generate an AI-powered reply to a mention (Mock LLM)

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Request Body:**
```json
{
  "managedEntityName": "The Quantum Paradox",
  "mentionContent": "This movie was terrible! Waste of money.",
  "sentiment": "NEGATIVE"
}
```

**Response:**
```json
{
  "generatedReply": "This is a mock LLM-generated reply. In production, this would be generated by an actual LLM based on the prompt: Generate a professional reply to the following negative mention: This movie was terrible! Waste of money."
}
```

**Status Code:** `200 OK`

---

### 18. Post Response

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

## Crisis Management APIs

### 19. Generate Crisis Plan

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

## Analytics APIs

### 20. Get Box Office Prediction

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

- **`SPIKE`** — every 5 minutes, scans each managed entity's rolling 60-minute window. Fires when the negative-sentiment ratio exceeds 1.5x the 7-day baseline (with a 10-mention minimum and a 30-minute dedup window).
- **`INFLUENCER_NEGATIVE`** — every 1 minute, picks up newly inserted `NEGATIVE` mentions (id-based watermark, bulk-insert friendly) whose author appears in the top-50 spreader list for any of the managed entity's keywords. Spreader lookups are cached for 10 minutes per keyword.

After an alert is persisted, `AlertDispatcher` fans it out to two async channels (failures are caught and logged — they do not block alert persistence):

- **Email** — `EmailChannel` interface with a log-only `NoopEmailChannel` `@Component` shipped by default (swap in SendGrid in prod). Subject is `[Aura] {entityName} negative spike`; body lists the top 3 most recent negative mentions for the entity with their permalinks.
- **Webhook** — `WebhookChannel` POSTs the alert JSON to every user's configured `alertWebhookUrl` (see [Set Alert Webhook URL](#set-alert-webhook-url)).

All routes below are JWT-protected — pass `Authorization: Bearer {jwt_token}`.

### List Alerts

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
- `currentValue` / `baselineValue` are the rolling and 7-day negative-sentiment ratios for `SPIKE` alerts; both are `0.0` for `INFLUENCER_NEGATIVE`.
- `sourceMentionId`, `matchedAuthor`, `permalink` are populated for `INFLUENCER_NEGATIVE` and `null` for `SPIKE`.
- `reason` is a server-rendered, 1-line human-readable summary suitable for direct display.

---

### Acknowledge Alert

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

### Dismiss Alert

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

### Set Alert Webhook URL

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

## AuraMath Proxy APIs

The following endpoints are thin wrappers over the upstream **AuraMath** service. Each wrapper forwards the request to the corresponding upstream route verbatim and preserves the upstream HTTP status code. Wrapper paths (`/v1/**`) **do not** require JWT authentication — the upstream service is responsible for its own auth. See the [AuraMath Proxy](#auramath-proxy-v1-healthz) section below for configuration, error envelopes, and runtime details.

### 21. Get Viral Seeds

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

### 22. Get Aspect Drivers

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

### 23. Get Top Spreaders

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

### 24. Find Lookalikes

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

### 25. Get User Profile

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

### 26. Get User Report

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

### 27. List Users

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

### 28. Get User Categories

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

### 29. Trigger User Sync

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

### 30. Get Potential Viewers for a Genre

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

### 31. Get Super Spreaders for a Genre

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

### 32. Get Channel Strategy for a Genre

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

### 33. List Targets

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

### 34. Diagnostic: Raw Author Mapping

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

### 35. Diagnostic: Temporal Audit

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

### 36. Diagnostic: Process User

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

The 16 wrapper endpoints are documented in detail as APIs #21–#36 in the [AuraMath Proxy APIs](#auramath-proxy-apis) section above.

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
