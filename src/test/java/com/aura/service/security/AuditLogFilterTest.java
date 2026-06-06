package com.aura.service.security;

import com.aura.service.entity.AuditLog;
import com.aura.service.repository.AuditLogRepository;
import com.aura.service.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuditLogFilterTest {

    private static final Instant NOW = Instant.parse("2026-06-06T12:00:00Z");

    // The service is concrete (and can't be inline-mocked here), so drive a real one off a mocked
    // repository. @Async is a no-op outside a Spring context, so record() runs synchronously.
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final AuditLogService auditLogService = new AuditLogService(auditLogRepository);
    private final AuditLogFilter filter = new AuditLogFilter(auditLogService, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, AuthorityUtils.NO_AUTHORITIES));
    }

    private AuditLog captureRecorded() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void records_who_what_when_and_success_for_authenticated_call() throws Exception {
        authenticateAs("alice");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mentions/42");
        request.setQueryString("status=NEW");
        request.addHeader("User-Agent", "JUnit");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        AuditLog entry = captureRecorded();
        assertThat(entry.getUsername()).isEqualTo("alice");
        assertThat(entry.getHttpMethod()).isEqualTo("GET");
        assertThat(entry.getPath()).isEqualTo("/api/mentions/42");
        assertThat(entry.getQueryString()).isEqualTo("status=NEW");
        assertThat(entry.getStatusCode()).isEqualTo(200);
        assertThat(entry.isSuccess()).isTrue();
        assertThat(entry.getUserAgent()).isEqualTo("JUnit");
        assertThat(entry.getTimestamp()).isEqualTo(NOW);
    }

    @Test
    void marks_4xx_responses_as_failures() throws Exception {
        authenticateAs("bob");
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/mentions/999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        filter.doFilter(request, response, new MockFilterChain());

        AuditLog entry = captureRecorded();
        assertThat(entry.getStatusCode()).isEqualTo(404);
        assertThat(entry.isSuccess()).isFalse();
    }

    @Test
    void unauthenticated_caller_is_recorded_as_anonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/entities/movie");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(captureRecorded().getUsername()).isEqualTo("anonymous");
    }

    @Test
    void captures_request_body_for_write_verbs() throws Exception {
        authenticateAs("carol");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/entities/movie");
        request.setContent("{\"name\":\"Karuppu\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        // The controller must still be able to read the body; the cached copy feeds the audit log.
        filter.doFilter(request, response, (req, res) -> req.getInputStream().readAllBytes());

        assertThat(captureRecorded().getRequestBody()).isEqualTo("{\"name\":\"Karuppu\"}");
    }

    @Test
    void redacts_request_body_for_auth_endpoints() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContent("{\"username\":\"alice\",\"password\":\"hunter2\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, (req, res) -> req.getInputStream().readAllBytes());

        AuditLog entry = captureRecorded();
        assertThat(entry.getRequestBody()).isEqualTo("[REDACTED]");
        assertThat(entry.getRequestBody()).doesNotContain("hunter2");
    }

    @Test
    void skips_documentation_and_health_endpoints() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verifyNoInteractions(auditLogRepository);
    }
}
