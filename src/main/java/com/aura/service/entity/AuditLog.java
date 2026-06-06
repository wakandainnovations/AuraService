package com.aura.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Immutable record of a single API call handled by the service: which endpoint was hit, by whom,
 * when, whether it succeeded, and the salient details of the request. One row is written per
 * request by {@link com.aura.service.security.AuditLogFilter}.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_username", columnList = "username"),
        @Index(name = "idx_audit_logs_timestamp", columnList = "timestamp"),
        @Index(name = "idx_audit_logs_path", columnList = "path")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** When the request completed. */
    @Column(nullable = false)
    private Instant timestamp;

    /** Authenticated principal that made the call, or {@code "anonymous"} when unauthenticated. */
    @Column(nullable = false)
    private String username;

    /** HTTP verb (GET/POST/PUT/PATCH/DELETE/...). */
    @Column(name = "http_method", nullable = false)
    private String httpMethod;

    /** Request URI path (without the query string), e.g. {@code /api/mentions/42}. */
    @Column(nullable = false)
    private String path;

    /** Raw query string, if any. */
    @Column(name = "query_string")
    private String queryString;

    /** HTTP status code returned to the client. */
    @Column(name = "status_code", nullable = false)
    private int statusCode;

    /** Convenience flag: true for 2xx/3xx responses, false otherwise. */
    @Column(nullable = false)
    private boolean success;

    /** Wall-clock time spent handling the request, in milliseconds. */
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    /** Caller's IP address (honours {@code X-Forwarded-For} when present). */
    @Column(name = "client_ip")
    private String clientIp;

    /** Caller's {@code User-Agent} header. */
    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    /**
     * Truncated request payload for write operations. Redacted for authentication endpoints so
     * credentials are never persisted.
     */
    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;
}
