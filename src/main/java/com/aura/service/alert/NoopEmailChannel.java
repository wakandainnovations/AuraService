package com.aura.service.alert;

import com.aura.service.entity.Mention;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

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
}
