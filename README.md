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

### 8. Get Average Entity Statistics

**Endpoint:** `GET /api/dashboard/{entityId}/stats`

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

### 9. Get Entity Statistics

**Endpoint:** `GET /api/dashboard/{entityId}/stats/avg`

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

### 10. Get Competitor Snapshot

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

### 11. Get Sentiment Over Time

**Endpoint:** `GET /api/dashboard/{entityId}/sentiment-over-time`

**Description:** Get time-series data for sentiment analysis

**Headers:**
```
Authorization: Bearer {jwt_token}
```

**Path Parameters:**
- `entityId` - Entity ID (e.g., 1)

**Query Parameters:**
- `period` - Time period (DAY, DAY15, DAY30, WEEK, or MONTH)
- `entityIds` - Comma-separated list of entity IDs to compare (e.g., 1,3,4)

**Example Request:**
```
GET /api/dashboard/1/sentiment-over-time?period=WEEK&entityIds=1,3
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

### 12. Get Platform Mentions

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

### 13. Get Filtered Mentions

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

## Interaction APIs

### 14. Generate Reply

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

### 15. Post Response

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

### 16. Generate Crisis Plan

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

### 17. Get Box Office Prediction

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

## Support

For issues or questions, please contact the development team
