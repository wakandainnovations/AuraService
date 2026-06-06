package com.aura.service.repository;

import com.aura.service.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Paged audit trail, newest first, with every filter optional. Each filter is gated by a boolean
     * flag rather than a {@code :param IS NULL} test: PostgreSQL cannot infer the type of a bare
     * {@code NULL} bind in an {@code IS NULL} check (it raises "could not determine data type of
     * parameter"), so each optional value appears only inside a typed comparison whose column tells
     * the planner its type. {@link com.aura.service.service.AuditLogService} derives the flags.
     */
    @Query("SELECT a FROM AuditLog a WHERE " +
            "(:filterUsername = false OR a.username = :username) AND " +
            "(:filterSuccess = false OR a.success = :success) AND " +
            "(:filterFrom = false OR a.timestamp >= :from) AND " +
            "(:filterTo = false OR a.timestamp <= :to) " +
            "ORDER BY a.timestamp DESC")
    Page<AuditLog> search(
            @Param("filterUsername") boolean filterUsername,
            @Param("username") String username,
            @Param("filterSuccess") boolean filterSuccess,
            @Param("success") Boolean success,
            @Param("filterFrom") boolean filterFrom,
            @Param("from") Instant from,
            @Param("filterTo") boolean filterTo,
            @Param("to") Instant to,
            Pageable pageable);
}
