package com.aura.service.service;

import com.aura.service.entity.AuditLog;
import com.aura.service.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Persists and reads {@link AuditLog} entries. Writes happen off the request thread so that
 * recording the audit trail never adds latency to (or fails) the API call being audited.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Records one API call. Runs asynchronously and swallows persistence errors: a failure to write
     * the audit trail must never break the request that triggered it (it is only logged).
     */
    @Async
    public void record(AuditLog entry) {
        try {
            auditLogRepository.save(entry);
        } catch (Exception ex) {
            log.warn("Failed to persist audit log for {} {} by {}: {}",
                    entry.getHttpMethod(), entry.getPath(), entry.getUsername(), ex.getMessage());
        }
    }

    /**
     * Paged audit trail, newest first. Every filter is optional — pass {@code null} to widen.
     */
    public Page<AuditLog> search(String username, Boolean success, Instant from, Instant to, Pageable pageable) {
        // A null argument disables that filter; the repository gates each one on these flags so no
        // untyped NULL ever reaches PostgreSQL's IS NULL check.
        return auditLogRepository.search(
                username != null, username,
                success != null, success,
                from != null, from,
                to != null, to,
                pageable);
    }
}
