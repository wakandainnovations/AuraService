package com.aura.service.service;

import com.aura.service.dto.ClonePlaybookRequest;
import com.aura.service.dto.PlaybookResponse;
import com.aura.service.dto.UpdatePlaybookRequest;
import com.aura.service.entity.CrisisPlan;
import com.aura.service.repository.CrisisPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Manages crisis plans as a reusable "playbook library": listing/filtering, post-draft
 * editing, and cloning a past plan into a fresh one.
 */
@Service
@RequiredArgsConstructor
public class PlaybookService {

    private final CrisisPlanRepository playbookRepository;

    /**
     * Lists playbooks, newest first. Each filter is optional: {@code entityId} scopes to one
     * managed entity, {@code tag} keeps only playbooks carrying that tag, and {@code favorite}
     * keeps only those matching the favorite flag.
     */
    public List<PlaybookResponse> list(Long entityId, String tag, Boolean favorite) {
        List<CrisisPlan> plans = (entityId != null)
                ? playbookRepository.findByEntityId(entityId)
                : playbookRepository.findAll();

        return plans.stream()
                .filter(p -> favorite == null || p.isFavorite() == favorite)
                .filter(p -> tag == null || tag.isBlank()
                        || (p.getTags() != null && p.getTags().contains(tag)))
                .sorted(Comparator.comparing(CrisisPlan::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Edits a playbook in place. Only the fields present on the request are changed, so the UI
     * can rename, retag, (un)favorite, or rewrite the AI-drafted text independently.
     */
    @Transactional
    public PlaybookResponse update(Long id, UpdatePlaybookRequest request) {
        CrisisPlan plan = require(id);

        if (request.getTitle() != null) {
            plan.setTitle(request.getTitle());
        }
        if (request.getPlanText() != null) {
            if (request.getPlanText().isBlank()) {
                throw new RuntimeException("planText must not be blank");
            }
            plan.setPlanText(request.getPlanText());
        }
        if (request.getTags() != null) {
            plan.setTags(new ArrayList<>(request.getTags()));
        }
        if (request.getIsFavorite() != null) {
            plan.setFavorite(request.getIsFavorite());
        }

        return toResponse(playbookRepository.save(plan));
    }

    /**
     * Starts a new playbook from a past one, copying its text and tags. The clone is owned by
     * the cloning user, is not favorited, and is named from {@code request.title} or, when that
     * is absent, "Copy of &lt;source title&gt;".
     */
    @Transactional
    public PlaybookResponse clone(Long createdBy, Long id, ClonePlaybookRequest request) {
        CrisisPlan source = require(id);

        String sourceTitle = source.getTitle() != null ? source.getTitle() : "playbook";
        String title = (request != null && request.getTitle() != null && !request.getTitle().isBlank())
                ? request.getTitle()
                : "Copy of " + sourceTitle;

        CrisisPlan clone = CrisisPlan.builder()
                .entityId(source.getEntityId())
                .mentionId(source.getMentionId())
                .title(title)
                .planText(source.getPlanText())
                .tags(source.getTags() == null ? new ArrayList<>() : new ArrayList<>(source.getTags()))
                .isFavorite(false)
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .build();

        return toResponse(playbookRepository.save(clone));
    }

    private CrisisPlan require(Long id) {
        return playbookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Playbook not found with id: " + id));
    }

    private PlaybookResponse toResponse(CrisisPlan p) {
        return new PlaybookResponse(
                p.getId(),
                p.getEntityId(),
                p.getMentionId(),
                p.getTitle(),
                p.getPlanText(),
                p.getTags() == null ? List.of() : List.copyOf(p.getTags()),
                p.isFavorite(),
                p.getCreatedBy(),
                p.getCreatedAt());
    }
}
