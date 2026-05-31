package com.aura.service.abuse;

import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbuseReportDispatcherTest {

    private AbuseReportDispatcher dispatcherWithAllStrategies() {
        return new AbuseReportDispatcher(List.of(
                new XAbuseReportStrategy(),
                new RedditAbuseReportStrategy(),
                new YoutubeAbuseReportStrategy(),
                new InstagramAbuseReportStrategy()
        ));
    }

    private AbuseReport report(Long id) {
        return AbuseReport.builder()
                .id(id)
                .mentionId(1L)
                .userId(1L)
                .category(AbuseReport.Category.HARASSMENT)
                .status(AbuseReport.Status.SUBMITTED)
                .build();
    }

    private Mention mention(Platform platform) {
        Mention m = new Mention();
        m.setId(1L);
        m.setPlatform(platform);
        m.setPostId("post_1");
        return m;
    }

    @Test
    void routesEachPlatformToItsOwnStrategy() {
        AbuseReportDispatcher dispatcher = dispatcherWithAllStrategies();

        assertThat(dispatcher.dispatch(report(10L), mention(Platform.X))).isEqualTo("x-mod-10");
        assertThat(dispatcher.dispatch(report(11L), mention(Platform.REDDIT))).isEqualTo("reddit-rpt-11");
        assertThat(dispatcher.dispatch(report(12L), mention(Platform.YOUTUBE))).isEqualTo("yt-flag-12");
        assertThat(dispatcher.dispatch(report(13L), mention(Platform.INSTAGRAM))).isEqualTo("ig-rpt-13");
    }

    @Test
    void returnsNullWhenNoStrategyRegisteredForPlatform() {
        AbuseReportDispatcher dispatcher = new AbuseReportDispatcher(List.of(new XAbuseReportStrategy()));

        assertThat(dispatcher.dispatch(report(20L), mention(Platform.REDDIT))).isNull();
    }

    @Test
    void returnsNullForNullArguments() {
        AbuseReportDispatcher dispatcher = dispatcherWithAllStrategies();

        assertThat(dispatcher.dispatch(null, mention(Platform.X))).isNull();
        assertThat(dispatcher.dispatch(report(30L), null)).isNull();
    }

    @Test
    void strategyFailureIsSwallowedAndReturnsNull() {
        AbuseReportStrategy boom = new AbuseReportStrategy() {
            @Override
            public Platform platform() {
                return Platform.X;
            }

            @Override
            public String submit(AbuseReport report, Mention mention) {
                throw new RuntimeException("platform API down");
            }
        };
        AbuseReportDispatcher dispatcher = new AbuseReportDispatcher(List.of(boom));

        assertThat(dispatcher.dispatch(report(40L), mention(Platform.X))).isNull();
    }
}
