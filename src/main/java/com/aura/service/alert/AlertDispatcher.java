package com.aura.service.alert;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.repository.ManagedEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertDispatcher {

    private final EmailChannel emailChannel;
    @Qualifier("webhookChannel")
    private final AlertChannel webhookChannel;
    private final ManagedEntityRepository entityRepository;

    public void dispatch(SentimentAlert alert) {
        if (alert == null) {
            return;
        }
        String entityName = lookupEntityName(alert.getManagedEntityId());
        safeSend("email", alert, entityName, emailChannel);
        safeSend("webhook", alert, entityName, webhookChannel);
    }

    private void safeSend(String channelName, SentimentAlert alert, String entityName, AlertChannel channel) {
        try {
            channel.send(alert, entityName);
        } catch (Exception e) {
            log.error("Failed to dispatch alert {} on {} channel", alert.getId(), channelName, e);
        }
    }

    private String lookupEntityName(Long entityId) {
        if (entityId == null) {
            return null;
        }
        return entityRepository.findById(entityId)
                .map(ManagedEntity::getName)
                .orElse(null);
    }
}
