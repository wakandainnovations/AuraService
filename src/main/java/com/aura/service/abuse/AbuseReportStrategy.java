package com.aura.service.abuse;

import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;

/**
 * Per-platform strategy for forwarding a persisted {@link AbuseReport} to the moderation
 * backend of the platform the reported {@link Mention} lives on.
 *
 * <p>Implementations are stubs today (log + return a fake ticket id); real platform calls
 * (Reddit {@code /api/report}, X media moderation endpoints, etc.) are plugged in here later.
 */
public interface AbuseReportStrategy {

    /** The platform this strategy handles. Used by {@link AbuseReportDispatcher} for routing. */
    Platform platform();

    /**
     * Forward the report to the platform and return the external ticket reference assigned by
     * that platform's moderation system.
     */
    String submit(AbuseReport report, Mention mention);
}
