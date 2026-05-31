package com.aura.service.abuse;

import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stub YouTube abuse-report strategy. Logs the report and returns a fake ticket id.
 *
 * <p>TODO: forward to the YouTube Data API moderation/flagging endpoints once API credentials
 * are wired in.
 */
@Slf4j
@Component
public class YoutubeAbuseReportStrategy implements AbuseReportStrategy {

    @Override
    public Platform platform() {
        return Platform.YOUTUBE;
    }

    @Override
    public String submit(AbuseReport report, Mention mention) {
        String externalRef = "yt-flag-" + report.getId();
        log.info("STUB YOUTUBE abuse report: reportId={} postId={} category={} -> externalRef={}",
                report.getId(), mention.getPostId(), report.getCategory(), externalRef);
        return externalRef;
    }
}
