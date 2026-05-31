package com.aura.service.service;

import com.aura.service.dto.WorkspaceExportBundle;
import com.aura.service.dto.WorkspaceImportResult;
import com.aura.service.entity.AlertRule;
import com.aura.service.entity.CrisisPlan;
import com.aura.service.entity.ReplyTemplate;
import com.aura.service.entity.SentimentAlert;
import com.aura.service.entity.User;
import com.aura.service.entity.UserEntityView;
import com.aura.service.repository.AlertRuleRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.ReplyTemplateRepository;
import com.aura.service.repository.UserEntityViewRepository;
import com.aura.service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceServiceTest {

    private static final String USERNAME = "alice";
    private static final Long USER_ID = 7L;

    private UserRepository userRepository;
    private ReplyTemplateRepository templateRepository;
    private AlertRuleRepository alertRuleRepository;
    private CrisisPlanRepository playbookRepository;
    private UserEntityViewRepository userEntityViewRepository;
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        templateRepository = mock(ReplyTemplateRepository.class);
        alertRuleRepository = mock(AlertRuleRepository.class);
        playbookRepository = mock(CrisisPlanRepository.class);
        userEntityViewRepository = mock(UserEntityViewRepository.class);
        service = new WorkspaceService(userRepository, templateRepository,
                alertRuleRepository, playbookRepository, userEntityViewRepository);

        User user = new User();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
    }

    @Test
    void export_gathersAllFourResourceTypesIntoOneBundle() {
        when(templateRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(
                ReplyTemplate.builder().id(1L).userId(USER_ID).name("Apology").body("Sorry")
                        .tone("empathetic").useCount(3)
                        .createdAt(Instant.parse("2026-05-01T00:00:00Z")).build()));
        when(alertRuleRepository.findByUserId(USER_ID)).thenReturn(List.of(
                AlertRule.builder().id(1L).userId(USER_ID).entityId(5L)
                        .kind(SentimentAlert.Kind.SPIKE).threshold(0.10)
                        .channels(List.of("EMAIL")).enabled(true).build()));
        when(playbookRepository.findByCreatedBy(USER_ID)).thenReturn(List.of(
                CrisisPlan.builder().id(1L).entityId(5L).mentionId(100L).title("Surge")
                        .planText("1. Acknowledge.").tags(List.of("review")).isFavorite(true)
                        .createdBy(USER_ID).createdAt(Instant.parse("2026-05-10T00:00:00Z")).build()));
        when(userEntityViewRepository.findByUserId(USER_ID)).thenReturn(List.of(
                new UserEntityView(1L, USER_ID, 5L, Instant.parse("2026-05-30T00:00:00Z"))));

        WorkspaceExportBundle bundle = service.export(USERNAME);

        assertThat(bundle.getFormat()).isEqualTo(WorkspaceExportBundle.FORMAT);
        assertThat(bundle.getVersion()).isEqualTo(WorkspaceExportBundle.CURRENT_VERSION);
        assertThat(bundle.getOwner()).isEqualTo(USERNAME);
        assertThat(bundle.getTemplates()).singleElement()
                .satisfies(t -> {
                    assertThat(t.getName()).isEqualTo("Apology");
                    assertThat(t.getUseCount()).isEqualTo(3);
                });
        assertThat(bundle.getAlertRules()).singleElement()
                .satisfies(r -> {
                    assertThat(r.getEntityId()).isEqualTo(5L);
                    assertThat(r.getKind()).isEqualTo(SentimentAlert.Kind.SPIKE);
                });
        assertThat(bundle.getPlaybooks()).singleElement()
                .satisfies(p -> {
                    assertThat(p.getTitle()).isEqualTo("Surge");
                    assertThat(p.isFavorite()).isTrue();
                });
        assertThat(bundle.getTrackedEntities()).singleElement()
                .satisfies(e -> assertThat(e.getEntityId()).isEqualTo(5L));
    }

    @Test
    void import_recreatesItemsOwnedByCallerAndReturnsCounts() {
        when(templateRepository.save(any(ReplyTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alertRuleRepository.save(any(AlertRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playbookRepository.save(any(CrisisPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userEntityViewRepository.findByUserIdAndEntityId(USER_ID, 5L)).thenReturn(Optional.empty());
        when(userEntityViewRepository.save(any(UserEntityView.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceExportBundle bundle = WorkspaceExportBundle.builder()
                .format(WorkspaceExportBundle.FORMAT)
                .version(WorkspaceExportBundle.CURRENT_VERSION)
                .owner("someone-else")
                .templates(List.of(WorkspaceExportBundle.TemplateItem.builder()
                        .name("Apology").body("Sorry").tone("empathetic").useCount(3)
                        .createdAt(Instant.parse("2026-05-01T00:00:00Z")).build()))
                .alertRules(List.of(WorkspaceExportBundle.AlertRuleItem.builder()
                        .entityId(5L).kind(SentimentAlert.Kind.SPIKE).threshold(0.10)
                        .channels(List.of("EMAIL")).enabled(true).build()))
                .playbooks(List.of(WorkspaceExportBundle.PlaybookItem.builder()
                        .entityId(5L).mentionId(100L).title("Surge").planText("1. Acknowledge.")
                        .tags(List.of("review")).isFavorite(true)
                        .createdAt(Instant.parse("2026-05-10T00:00:00Z")).build()))
                .trackedEntities(List.of(WorkspaceExportBundle.TrackedEntityItem.builder()
                        .entityId(5L).lastSeenAt(Instant.parse("2026-05-30T00:00:00Z")).build()))
                .build();

        WorkspaceImportResult result = service.importWorkspace(USERNAME, bundle);

        assertThat(result.getTemplatesImported()).isEqualTo(1);
        assertThat(result.getAlertRulesImported()).isEqualTo(1);
        assertThat(result.getPlaybooksImported()).isEqualTo(1);
        assertThat(result.getTrackedEntitiesImported()).isEqualTo(1);

        // Ownership is always reassigned to the authenticated user, never trusted from the file.
        ArgumentCaptor<ReplyTemplate> template = ArgumentCaptor.forClass(ReplyTemplate.class);
        verify(templateRepository).save(template.capture());
        assertThat(template.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(template.getValue().getUseCount()).isEqualTo(3);

        ArgumentCaptor<AlertRule> rule = ArgumentCaptor.forClass(AlertRule.class);
        verify(alertRuleRepository).save(rule.capture());
        assertThat(rule.getValue().getUserId()).isEqualTo(USER_ID);

        ArgumentCaptor<CrisisPlan> playbook = ArgumentCaptor.forClass(CrisisPlan.class);
        verify(playbookRepository).save(playbook.capture());
        assertThat(playbook.getValue().getCreatedBy()).isEqualTo(USER_ID);
        assertThat(playbook.getValue().isFavorite()).isTrue();
    }

    @Test
    void import_upsertsExistingTrackedEntityInsteadOfDuplicating() {
        UserEntityView existing = new UserEntityView(9L, USER_ID, 5L, Instant.parse("2020-01-01T00:00:00Z"));
        when(userEntityViewRepository.findByUserIdAndEntityId(USER_ID, 5L)).thenReturn(Optional.of(existing));
        when(userEntityViewRepository.save(any(UserEntityView.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceExportBundle bundle = WorkspaceExportBundle.builder()
                .format(WorkspaceExportBundle.FORMAT)
                .version(WorkspaceExportBundle.CURRENT_VERSION)
                .trackedEntities(List.of(WorkspaceExportBundle.TrackedEntityItem.builder()
                        .entityId(5L).lastSeenAt(Instant.parse("2026-05-30T00:00:00Z")).build()))
                .build();

        WorkspaceImportResult result = service.importWorkspace(USERNAME, bundle);

        assertThat(result.getTrackedEntitiesImported()).isEqualTo(1);
        ArgumentCaptor<UserEntityView> view = ArgumentCaptor.forClass(UserEntityView.class);
        verify(userEntityViewRepository).save(view.capture());
        // Same row id reused (update), with a refreshed last-seen timestamp.
        assertThat(view.getValue().getId()).isEqualTo(9L);
        assertThat(view.getValue().getLastSeenAt()).isEqualTo(Instant.parse("2026-05-30T00:00:00Z"));
    }

    @Test
    void import_rejectsForeignFormat() {
        WorkspaceExportBundle bundle = WorkspaceExportBundle.builder()
                .format("competitor-export")
                .version(WorkspaceExportBundle.CURRENT_VERSION)
                .build();

        assertThatThrownBy(() -> service.importWorkspace(USERNAME, bundle))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("format");

        verify(templateRepository, never()).save(any());
    }

    @Test
    void import_rejectsUnsupportedVersion() {
        WorkspaceExportBundle bundle = WorkspaceExportBundle.builder()
                .format(WorkspaceExportBundle.FORMAT)
                .version(WorkspaceExportBundle.CURRENT_VERSION + 1)
                .build();

        assertThatThrownBy(() -> service.importWorkspace(USERNAME, bundle))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("version");

        verify(templateRepository, never()).save(any());
    }
}
