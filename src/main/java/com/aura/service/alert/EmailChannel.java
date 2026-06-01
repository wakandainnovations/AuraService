package com.aura.service.alert;

import com.aura.service.dto.WhatsChangedResponse;
import com.aura.service.entity.User;

import java.util.List;
import java.util.Map;

public interface EmailChannel extends AlertChannel {

    /**
     * @param impactHighlights short, display-ready sentences reflecting the user's accumulated
     *                         investment (e.g. "Your playbook library has handled 12 crises.");
     *                         may be empty. Rendered ahead of the overnight deltas so the digest
     *                         opens on what the user has built, not just what changed.
     */
    void sendDigest(User user, String subject, Map<String, WhatsChangedResponse> entries,
                    List<String> impactHighlights);
}
