package com.aura.service.service;

import com.aura.service.dto.WorkspaceExportBundle;
import com.aura.service.dto.WorkspaceImportResult;
import com.aura.service.entity.AlertRule;
import com.aura.service.entity.CrisisPlan;
import com.aura.service.entity.ReplyTemplate;
import com.aura.service.entity.User;
import com.aura.service.entity.UserEntityView;
import com.aura.service.repository.AlertRuleRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.ReplyTemplateRepository;
import com.aura.service.repository.UserEntityViewRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Backs up and restores a single user's workspace as one proprietary JSON document
 * (see {@link WorkspaceExportBundle}). Export gathers the user's reply templates, alert
 * rules, playbooks, and tracked entities; import replays that document back into the
 * authenticated user's account.
 * <p>
 * Import is <strong>additive</strong>: templates, alert rules, and playbooks are recreated
 * as new rows owned by the calling user (re-importing the same bundle duplicates them), and
 * tracked entities are upserted by {@code entityId}. Import never deletes existing data.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final UserRepository userRepository;
    private final ReplyTemplateRepository templateRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final CrisisPlanRepository playbookRepository;
    private final UserEntityViewRepository userEntityViewRepository;

    public WorkspaceExportBundle export(String username) {
        Long userId = resolveUserId(username);

        List<WorkspaceExportBundle.TemplateItem> templates =
                templateRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(t -> WorkspaceExportBundle.TemplateItem.builder()
                                .name(t.getName())
                                .body(t.getBody())
                                .tone(t.getTone())
                                .useCount(t.getUseCount())
                                .createdAt(t.getCreatedAt())
                                .build())
                        .toList();

        List<WorkspaceExportBundle.AlertRuleItem> alertRules =
                alertRuleRepository.findByUserId(userId).stream()
                        .map(r -> WorkspaceExportBundle.AlertRuleItem.builder()
                                .entityId(r.getEntityId())
                                .kind(r.getKind())
                                .threshold(r.getThreshold())
                                .channels(r.getChannels() == null ? new ArrayList<>() : new ArrayList<>(r.getChannels()))
                                .enabled(r.isEnabled())
                                .build())
                        .toList();

        List<WorkspaceExportBundle.PlaybookItem> playbooks =
                playbookRepository.findByCreatedBy(userId).stream()
                        .map(p -> WorkspaceExportBundle.PlaybookItem.builder()
                                .entityId(p.getEntityId())
                                .mentionId(p.getMentionId())
                                .title(p.getTitle())
                                .planText(p.getPlanText())
                                .tags(p.getTags() == null ? new ArrayList<>() : new ArrayList<>(p.getTags()))
                                .isFavorite(p.isFavorite())
                                .createdAt(p.getCreatedAt())
                                .build())
                        .toList();

        List<WorkspaceExportBundle.TrackedEntityItem> trackedEntities =
                userEntityViewRepository.findByUserId(userId).stream()
                        .map(v -> WorkspaceExportBundle.TrackedEntityItem.builder()
                                .entityId(v.getEntityId())
                                .lastSeenAt(v.getLastSeenAt())
                                .build())
                        .toList();

        return WorkspaceExportBundle.builder()
                .format(WorkspaceExportBundle.FORMAT)
                .version(WorkspaceExportBundle.CURRENT_VERSION)
                .exportedAt(Instant.now())
                .owner(username)
                .templates(templates)
                .alertRules(alertRules)
                .playbooks(playbooks)
                .trackedEntities(trackedEntities)
                .build();
    }

    @Transactional
    public WorkspaceImportResult importWorkspace(String username, WorkspaceExportBundle bundle) {
        validateBundle(bundle);
        Long userId = resolveUserId(username);
        Instant now = Instant.now();

        int templatesImported = 0;
        if (bundle.getTemplates() != null) {
            for (WorkspaceExportBundle.TemplateItem item : bundle.getTemplates()) {
                templateRepository.save(ReplyTemplate.builder()
                        .userId(userId)
                        .name(item.getName())
                        .body(item.getBody())
                        .tone(item.getTone())
                        .useCount(Math.max(0, item.getUseCount()))
                        .createdAt(item.getCreatedAt() != null ? item.getCreatedAt() : now)
                        .build());
                templatesImported++;
            }
        }

        int alertRulesImported = 0;
        if (bundle.getAlertRules() != null) {
            for (WorkspaceExportBundle.AlertRuleItem item : bundle.getAlertRules()) {
                alertRuleRepository.save(AlertRule.builder()
                        .userId(userId)
                        .entityId(item.getEntityId())
                        .kind(item.getKind())
                        .threshold(item.getThreshold())
                        .channels(item.getChannels() == null ? new ArrayList<>() : new ArrayList<>(item.getChannels()))
                        .enabled(item.isEnabled())
                        .build());
                alertRulesImported++;
            }
        }

        int playbooksImported = 0;
        if (bundle.getPlaybooks() != null) {
            for (WorkspaceExportBundle.PlaybookItem item : bundle.getPlaybooks()) {
                playbookRepository.save(CrisisPlan.builder()
                        .entityId(item.getEntityId())
                        .mentionId(item.getMentionId())
                        .title(item.getTitle())
                        .planText(item.getPlanText())
                        .tags(item.getTags() == null ? new ArrayList<>() : new ArrayList<>(item.getTags()))
                        .isFavorite(item.isFavorite())
                        .createdBy(userId)
                        .createdAt(item.getCreatedAt() != null ? item.getCreatedAt() : now)
                        .build());
                playbooksImported++;
            }
        }

        int trackedEntitiesImported = 0;
        if (bundle.getTrackedEntities() != null) {
            for (WorkspaceExportBundle.TrackedEntityItem item : bundle.getTrackedEntities()) {
                if (item.getEntityId() == null) {
                    continue;
                }
                UserEntityView view = userEntityViewRepository
                        .findByUserIdAndEntityId(userId, item.getEntityId())
                        .orElseGet(() -> {
                            UserEntityView v = new UserEntityView();
                            v.setUserId(userId);
                            v.setEntityId(item.getEntityId());
                            return v;
                        });
                view.setLastSeenAt(item.getLastSeenAt() != null ? item.getLastSeenAt() : now);
                userEntityViewRepository.save(view);
                trackedEntitiesImported++;
            }
        }

        return WorkspaceImportResult.builder()
                .templatesImported(templatesImported)
                .alertRulesImported(alertRulesImported)
                .playbooksImported(playbooksImported)
                .trackedEntitiesImported(trackedEntitiesImported)
                .build();
    }

    private void validateBundle(WorkspaceExportBundle bundle) {
        if (bundle == null) {
            throw new RuntimeException("Workspace import body is required");
        }
        if (!WorkspaceExportBundle.FORMAT.equals(bundle.getFormat())) {
            throw new RuntimeException("Unrecognized workspace format; expected '"
                    + WorkspaceExportBundle.FORMAT + "'");
        }
        if (bundle.getVersion() != WorkspaceExportBundle.CURRENT_VERSION) {
            throw new RuntimeException("Unsupported workspace version: " + bundle.getVersion()
                    + "; expected " + WorkspaceExportBundle.CURRENT_VERSION);
        }
    }

    private Long resolveUserId(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + username));
    }
}
