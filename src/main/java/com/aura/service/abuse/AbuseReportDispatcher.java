package com.aura.service.abuse;

import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routes a persisted {@link AbuseReport} to the {@link AbuseReportStrategy} for the reported
 * mention's {@link Platform} and returns the external ticket reference the strategy produced.
 *
 * <p>Strategies are discovered from the Spring context, so adding a new platform is just adding
 * a new {@code @Component} implementing {@link AbuseReportStrategy}.
 */
@Slf4j
@Component
public class AbuseReportDispatcher {

    private final Map<Platform, AbuseReportStrategy> strategies = new EnumMap<>(Platform.class);

    public AbuseReportDispatcher(List<AbuseReportStrategy> strategies) {
        for (AbuseReportStrategy strategy : strategies) {
            AbuseReportStrategy previous = this.strategies.put(strategy.platform(), strategy);
            if (previous != null) {
                log.warn("Multiple abuse-report strategies registered for platform {}: {} replaced {}",
                        strategy.platform(), strategy.getClass().getSimpleName(),
                        previous.getClass().getSimpleName());
            }
        }
    }

    /**
     * Forward the report to the platform-specific moderation backend and return the external
     * ticket reference, or {@code null} when no strategy handles the mention's platform or the
     * strategy fails. Never throws — forwarding failures must not roll back the persisted report.
     */
    public String dispatch(AbuseReport report, Mention mention) {
        if (report == null || mention == null) {
            return null;
        }
        Platform platform = mention.getPlatform();
        AbuseReportStrategy strategy = strategies.get(platform);
        if (strategy == null) {
            log.warn("No abuse-report strategy registered for platform {}; report {} not forwarded",
                    platform, report.getId());
            return null;
        }
        try {
            return strategy.submit(report, mention);
        } catch (Exception e) {
            log.error("Failed to forward abuse report {} to platform {}", report.getId(), platform, e);
            return null;
        }
    }
}
