package com.aura.service.service;

import com.aura.service.dto.AlertResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.SentimentAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AlertService {

    private final SentimentAlertRepository alertRepository;
    private final ManagedEntityRepository entityRepository;
    private final Clock clock;

    public Page<AlertResponse> list(Long entityId, SentimentAlert.Status status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("triggeredAt").descending());
        Page<SentimentAlert> alerts = alertRepository.findFiltered(entityId, status, pageable);

        List<Long> entityIds = alerts.getContent().stream()
                .map(SentimentAlert::getManagedEntityId)
                .distinct()
                .toList();
        Map<Long, String> nameById = entityIds.isEmpty()
                ? Map.of()
                : entityRepository.findAllById(entityIds).stream()
                        .collect(Collectors.toMap(ManagedEntity::getId, ManagedEntity::getName));

        return alerts.map(a -> toResponse(a, nameById.get(a.getManagedEntityId())));
    }

    @Transactional
    public Optional<AlertResponse> ack(Long alertId, String username) {
        return alertRepository.findById(alertId).map(alert -> {
            alert.setStatus(SentimentAlert.Status.ACKED);
            alert.setAckedAt(clock.instant());
            alert.setAckedBy(username);
            SentimentAlert saved = alertRepository.save(alert);
            return toResponse(saved, lookupEntityName(saved.getManagedEntityId()));
        });
    }

    @Transactional
    public Optional<AlertResponse> dismiss(Long alertId, String reason, String username) {
        return alertRepository.findById(alertId).map(alert -> {
            alert.setStatus(SentimentAlert.Status.DISMISSED);
            alert.setDismissedAt(clock.instant());
            alert.setDismissedBy(username);
            alert.setDismissReason(reason);
            SentimentAlert saved = alertRepository.save(alert);
            return toResponse(saved, lookupEntityName(saved.getManagedEntityId()));
        });
    }

    private String lookupEntityName(Long entityId) {
        if (entityId == null) return null;
        return entityRepository.findById(entityId).map(ManagedEntity::getName).orElse(null);
    }

    private AlertResponse toResponse(SentimentAlert a, String entityName) {
        AlertResponse r = new AlertResponse();
        r.setId(a.getId());
        r.setManagedEntityId(a.getManagedEntityId());
        r.setEntityName(entityName);
        r.setKind(a.getKind());
        r.setStatus(a.getStatus());
        r.setTriggeredAt(a.getTriggeredAt());
        r.setCurrentValue(a.getCurrentValue());
        r.setBaselineValue(a.getBaselineValue());
        r.setSourceMentionId(a.getSourceMentionId());
        r.setMatchedAuthor(a.getMatchedAuthor());
        r.setPermalink(a.getPermalink());
        r.setAckedAt(a.getAckedAt());
        r.setAckedBy(a.getAckedBy());
        r.setDismissedAt(a.getDismissedAt());
        r.setDismissedBy(a.getDismissedBy());
        r.setDismissReason(a.getDismissReason());
        r.setReason(buildReason(a, entityName));
        return r;
    }

    private String buildReason(SentimentAlert a, String entityName) {
        String name = entityName != null ? entityName : ("entity #" + a.getManagedEntityId());
        if (a.getKind() == null) {
            return "Alert fired for " + name;
        }
        return switch (a.getKind()) {
            case SPIKE -> String.format(Locale.ROOT,
                    "Negative-sentiment ratio rose to %.0f%% (baseline %.0f%%) for %s",
                    a.getCurrentValue() * 100.0, a.getBaselineValue() * 100.0, name);
            case INFLUENCER_NEGATIVE -> {
                String author = a.getMatchedAuthor() != null ? a.getMatchedAuthor() : "a top-50 spreader";
                yield String.format(Locale.ROOT,
                        "Top-50 spreader %s posted a negative mention about %s",
                        author, name);
            }
        };
    }
}
