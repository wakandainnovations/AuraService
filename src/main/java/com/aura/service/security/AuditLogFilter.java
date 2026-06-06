package com.aura.service.security;

import com.aura.service.entity.AuditLog;
import com.aura.service.service.AuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

/**
 * Writes an {@link AuditLog} row for every API call: which endpoint was invoked, by whom, when,
 * whether it succeeded, and the salient request details.
 *
 * <p>Deliberately positioned <em>after</em> {@link JwtAuthenticationFilter} in the security chain so
 * that the authenticated principal is available, and so the response status reflects the fully
 * handled request. It is wired explicitly in {@code SecurityConfig} (not a {@code @Component}) to
 * keep it inside the security chain and avoid a duplicate servlet-container registration.
 */
@RequiredArgsConstructor
public class AuditLogFilter extends OncePerRequestFilter {

    /** Cap on the persisted request body so a large payload can never bloat the audit table. */
    private static final int MAX_BODY_CHARS = 2000;
    private static final String ANONYMOUS = "anonymous";
    private static final String REDACTED = "[REDACTED]";

    private final AuditLogService auditLogService;
    private final Clock clock;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Wrap so the request body can be read for the audit trail without consuming it for the
        // controller. Only write verbs carry a body worth recording.
        boolean captureBody = hasBody(request);
        HttpServletRequest effectiveRequest =
                captureBody ? new ContentCachingRequestWrapper(request) : request;

        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(effectiveRequest, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            recordSafely(effectiveRequest, response, durationMs, captureBody);
        }
    }

    /** Audit endpoints/static assets we do not want to record (docs, health checks, console). */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/h2-console")
                || path.equals("/openapi.yaml")
                || path.equals("/healthz")
                || path.equals("/favicon.ico");
    }

    /** Build and hand off the audit entry; never let auditing surface an error to the caller. */
    private void recordSafely(HttpServletRequest request, HttpServletResponse response,
                              long durationMs, boolean captureBody) {
        try {
            int status = response.getStatus();
            AuditLog entry = AuditLog.builder()
                    .timestamp(Instant.now(clock))
                    .username(currentUsername())
                    .httpMethod(request.getMethod())
                    .path(request.getRequestURI())
                    .queryString(request.getQueryString())
                    .statusCode(status)
                    .success(status >= 200 && status < 400)
                    .durationMs(durationMs)
                    .clientIp(clientIp(request))
                    .userAgent(request.getHeader("User-Agent"))
                    .requestBody(captureBody ? extractBody(request) : null)
                    .build();
            auditLogService.record(entry);
        } catch (Exception ignored) {
            // Auditing is best-effort and must never affect the response.
        }
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return ANONYMOUS;
        }
        // Spring's anonymous principal surfaces as "anonymousUser"; normalise it.
        return "anonymousUser".equals(auth.getName()) ? ANONYMOUS : auth.getName();
    }

    private boolean hasBody(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    /**
     * Returns the captured body, truncated to {@link #MAX_BODY_CHARS}. Bodies sent to the auth
     * endpoints are redacted so credentials are never written to the audit table.
     */
    private String extractBody(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/auth")) {
            return REDACTED;
        }
        if (!(request instanceof ContentCachingRequestWrapper wrapper)) {
            return null;
        }
        byte[] buf = wrapper.getContentAsByteArray();
        if (buf.length == 0) {
            return null;
        }
        String body = new String(buf, StandardCharsets.UTF_8);
        return body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) + "...[truncated]" : body;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            // X-Forwarded-For may be a comma-separated chain; the client is the first entry.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
