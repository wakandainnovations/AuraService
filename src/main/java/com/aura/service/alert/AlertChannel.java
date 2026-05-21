package com.aura.service.alert;

import com.aura.service.entity.SentimentAlert;

public interface AlertChannel {
    void send(SentimentAlert alert, String entityName);
}
