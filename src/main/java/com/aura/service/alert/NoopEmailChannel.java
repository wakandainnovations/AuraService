package com.aura.service.alert;

import com.aura.service.dto.WhatsChangedResponse;
import com.aura.service.entity.Mention;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.entity.User;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NoopEmailChannel implements EmailChannel {

    private final MentionRepository mentionRepository;

    @Async
    @Override
    public void send(SentimentAlert alert, String entityName) {
        String name = entityName != null ? entityName : ("entity #" + alert.getManagedEntityId());
        String subject = "[Aura] " + name + " negative spike";

        List<Mention> top = mentionRepository
                .findTop3ByManagedEntityIdAndSentimentOrderByPostDateDesc(
                        alert.getManagedEntityId(), Sentiment.NEGATIVE);

        StringBuilder body = new StringBuilder();
        body.append("Alert ").append(alert.getId())
                .append(" (").append(alert.getKind()).append(") triggered at ")
                .append(alert.getTriggeredAt()).append(".\n\n");
        body.append("Top 3 recent negative mentions:\n");
        if (top.isEmpty()) {
            body.append("  (none found)\n");
        } else {
            for (Mention m : top) {
                String permalink = m.getPermalink() != null ? m.getPermalink() : "(no permalink)";
                String author = m.getAuthor() != null ? m.getAuthor() : "unknown";
                body.append("  - @").append(author).append(": ").append(permalink).append('\n');
            }
        }
        log.info("EMAIL alert={} subject=\"{}\"\n{}", alert.getId(), subject, body);
    }

    @Async
    @Override
    public void sendDigest(User user, String subject, Map<String, WhatsChangedResponse> entries) {
        StringBuilder body = new StringBuilder();
        body.append("Morning digest for ").append(user.getUsername()).append("\n\n");
        for (Map.Entry<String, WhatsChangedResponse> entry : entries.entrySet()) {
            WhatsChangedResponse delta = entry.getValue();
            body.append("--- ").append(entry.getKey()).append(" ---\n");
            body.append("  Sentiment delta : ").append(fmt(delta.getSentimentScoreDelta())).append('\n');
            body.append("  New mentions    : ").append(nullSafe(delta.getNewMentionsCount())).append('\n');
            body.append("  New negatives   : ").append(nullSafe(delta.getNewNegativeCount())).append('\n');
            body.append("  Super-spreaders : ").append(nullSafe(delta.getNewSuperSpreaderCount())).append('\n');
            Map<String, Double> comp = delta.getCompetitorDelta();
            if (comp != null && !comp.isEmpty()) {
                body.append("  Competitors     : ").append(comp).append('\n');
            }
            body.append('\n');
        }
        log.info("EMAIL DIGEST to={} subject=\"{}\"\n{}", user.getUsername(), subject, body);
    }

    private static String fmt(Double v) {
        return v != null ? String.format("%+.2f", v) : "0";
    }

    private static String nullSafe(Long v) {
        return v != null ? v.toString() : "0";
    }
}
