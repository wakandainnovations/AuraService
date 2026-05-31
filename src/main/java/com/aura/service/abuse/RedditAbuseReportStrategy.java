package com.aura.service.abuse;

import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stub Reddit abuse-report strategy. Logs the report and returns a fake ticket id.
 *
 * <p>TODO: forward to the Reddit {@code POST /api/report} endpoint once API credentials are wired in.
 */
@Slf4j
@Component
public class RedditAbuseReportStrategy implements AbuseReportStrategy {

    @Override
    public Platform platform() {
        return Platform.REDDIT;
    }

    @Override
    public String submit(AbuseReport report, Mention mention) {
        String externalRef = "reddit-rpt-" + report.getId();
        log.info("STUB REDDIT abuse report: reportId={} postId={} category={} -> externalRef={}",
                report.getId(), mention.getPostId(), report.getCategory(), externalRef);
        return externalRef;
    }
}
