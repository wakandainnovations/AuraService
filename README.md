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

Below, `$BASE=http://localhost:8080` is the wrapper service. All bodies are forwarded verbatim.

| # | Wrapper | Upstream | Cache |
| --: | --- | --- | --- |
| 1 | `GET /v1/viral-seeds?keyword=<k>` | `GET /api/marketing/viral-seeds?keyword=<k>` | 60 s |
| 2 | `GET /v1/aspect-drivers/{keyword}` | `GET /api/marketing/aspect-drivers/{keyword}` | 60 s |
| 3 | `GET /v1/top-spreaders/{keyword}` | `GET /api/marketing/top-50-spreaders/{keyword}` | 60 s |
| 4 | `POST /v1/find-lookalikes` | `POST /api/marketing/find-lookalikes` | — |
| 5 | `GET /v1/users/{globalUserId}/profile` | `GET /api/marketing/user-profile/{globalUserId}` | 60 s |
| 6 | `GET /v1/users/{author}/report` | `GET /api/marketing/user-report/{author}` | — (side-effect) |
| 7 | `GET /v1/users?…` | `GET /api/marketing/users?…` | 60 s |
| 8 | `GET /v1/users/categories` | `GET /api/marketing/users/categories` | 5 min |
| 9 | `POST /v1/users/sync` | `POST /api/marketing/users/sync` | — (10-min read timeout) |
| 10 | `GET /v1/genres/{genre}/potential-viewers` | `GET /api/marketing/genre/{genre}/potential-viewers` | 60 s |
| 11 | `GET /v1/genres/{genre}/super-spreaders` | `GET /api/marketing/genre/{genre}/super-spreaders` | 60 s |
| 12 | `GET /v1/genres/{genre}/channel-strategy` | `GET /api/marketing/genre/{genre}/channel-strategy` | 60 s |
| 13 | `GET /v1/targets?…` | `GET /v1/targets?…` | 60 s |
| 14 | `GET /v1/diagnostics/raw-mapping/{author}` | `GET /api/test/raw-mapping/{author}` | — |
| 15 | `GET /v1/diagnostics/temporal-audit/{author}` | `GET /api/test/temporal-audit/{author}` | — |
| 16 | `GET /v1/diagnostics/process-user/{author}` | `GET /test/process-user/{author}` (note: `/test`, not `/api/test`) | — |

```bash
# 1. Viral seeds
curl -s "$BASE/v1/viral-seeds?keyword=fantasy"

# 2. Aspect drivers
curl -s "$BASE/v1/aspect-drivers/fantasy"

# 3. Top spreaders
curl -s "$BASE/v1/top-spreaders/fantasy"

# 4. Find lookalikes  (400 if seedAuthorId missing or blank; no upstream call)
curl -s -X POST "$BASE/v1/find-lookalikes" \
     -H 'Content-Type: application/json' \
     -d '{"seedAuthorId":"author-123"}'

# 5. User profile
curl -s "$BASE/v1/users/u-42/profile"

# 6. User report (upstream persists a categorisation row — NOT cached)
curl -s "$BASE/v1/users/alice/report"

# 7. Users with optional filters
curl -s "$BASE/v1/users?audienceClassification=GenZ&influenceTier=TIER_1&primaryPlatform=TWITTER"

# 8. User categories (5-min TTL)
curl -s "$BASE/v1/users/categories"

# 9. Trigger upstream user sync (slow; 10-min read timeout)
curl -s -X POST "$BASE/v1/users/sync"

# 10. Potential viewers for a genre
curl -s "$BASE/v1/genres/thriller/potential-viewers"

# 11. Super spreaders for a genre
curl -s "$BASE/v1/genres/sci-fi/super-spreaders"

# 12. Channel strategy for a genre
curl -s "$BASE/v1/genres/horror/channel-strategy"

# 13. Targets with optional filters (minInfluenceScore defaults to 0.0)
curl -s "$BASE/v1/targets?genre=drama&minInfluenceScore=12.5&platform=TIKTOK"

# 14. Diagnostic: raw author mapping
curl -s "$BASE/v1/diagnostics/raw-mapping/alice"

# 15. Diagnostic: temporal audit
curl -s "$BASE/v1/diagnostics/temporal-audit/alice"

# 16. Diagnostic: process user (upstream path is /test/..., not /api/test/...)
curl -s "$BASE/v1/diagnostics/process-user/alice"
```

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
