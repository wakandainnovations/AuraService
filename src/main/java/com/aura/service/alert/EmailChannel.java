package com.aura.service.alert;

import com.aura.service.dto.WhatsChangedResponse;
import com.aura.service.entity.User;

import java.util.Map;

public interface EmailChannel extends AlertChannel {

    void sendDigest(User user, String subject, Map<String, WhatsChangedResponse> entries);
}
