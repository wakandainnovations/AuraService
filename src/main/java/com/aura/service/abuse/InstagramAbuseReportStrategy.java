package com.aura.service.abuse;

import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stub Instagram abuse-report strategy. Logs the report and returns a fake ticket id.
 *
 * <p>TODO: forward to the Instagram Graph API moderation endpoints once API credentials are wired in.
 */
@Slf4j
@Component
public class InstagramAbuseReportStrategy implements AbuseReportStrategy {

    @Override
    public Platform platform() {
        return Platform.INSTAGRAM;
    }

    @Override
    public String submit(AbuseReport report, Mention mention) {
        String externalRef = "ig-rpt-" + report.getId();
        log.info("STUB INSTAGRAM abuse report: reportId={} postId={} category={} -> externalRef={}",
                report.getId(), mention.getPostId(), report.getCategory(), externalRef);
        return externalRef;
    }
}
