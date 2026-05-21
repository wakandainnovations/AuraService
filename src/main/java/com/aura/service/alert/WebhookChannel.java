package com.aura.service.alert;

import com.aura.service.entity.SentimentAlert;
import com.aura.service.entity.User;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookChannel implements AlertChannel {

    private final UserRepository userRepository;
    private final RestClient restClient = RestClient.create();

    @Async
    @Override
    public void send(SentimentAlert alert, String entityName) {
        List<User> users = userRepository.findAll();
        Map<String, Object> payload = buildPayload(alert, entityName);
        for (User user : users) {
            String url = user.getAlertWebhookUrl();
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.error("Webhook POST failed for user {} url={}", user.getUsername(), url, e);
            }
        }
    }

    private Map<String, Object> buildPayload(SentimentAlert alert, String entityName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", alert.getId());
        payload.put("managedEntityId", alert.getManagedEntityId());
        payload.put("entityName", entityName);
        payload.put("kind", alert.getKind());
        payload.put("status", alert.getStatus());
        payload.put("triggeredAt", alert.getTriggeredAt());
        payload.put("currentValue", alert.getCurrentValue());
        payload.put("baselineValue", alert.getBaselineValue());
        payload.put("sourceMentionId", alert.getSourceMentionId());
        payload.put("matchedAuthor", alert.getMatchedAuthor());
        payload.put("permalink", alert.getPermalink());
        return payload;
    }
}
