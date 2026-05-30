package com.aura.service.service;

import com.aura.service.dto.UpdateReplyTemplateRequest;
import com.aura.service.entity.ReplyTemplate;
import com.aura.service.repository.ReplyTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplyTemplateServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long OTHER_USER_ID = 99L;
    private static final Long TEMPLATE_ID = 42L;

    private ReplyTemplateRepository templateRepository;
    private ReplyTemplateService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(ReplyTemplateRepository.class);
        service = new ReplyTemplateService(templateRepository);
    }

    private ReplyTemplate ownedTemplate() {
        return ReplyTemplate.builder()
                .id(TEMPLATE_ID)
                .userId(OWNER_ID)
                .name("Apology")
                .body("Thanks for the feedback — we hear you.")
                .tone("empathetic")
                .useCount(3)
                .createdAt(Instant.parse("2026-05-01T00:00:00Z"))
                .build();
    }

    @Test
    void requireOwnedTemplate_returnsTemplateForOwner() {
        ReplyTemplate template = ownedTemplate();
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));

        ReplyTemplate result = service.requireOwnedTemplate(OWNER_ID, TEMPLATE_ID);

        assertThat(result).isSameAs(template);
    }

    @Test
    void requireOwnedTemplate_throwsWhenTemplateMissing() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireOwnedTemplate(OWNER_ID, TEMPLATE_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void requireOwnedTemplate_throwsWhenOwnedByAnotherUser() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(ownedTemplate()));

        assertThatThrownBy(() -> service.requireOwnedTemplate(OTHER_USER_ID, TEMPLATE_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void useTemplate_incrementsUseCountAndReturnsBodyForOwner() {
        ReplyTemplate template = ownedTemplate();
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateRepository.save(any(ReplyTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        String body = service.useTemplate(OWNER_ID, TEMPLATE_ID);

        assertThat(body).isEqualTo("Thanks for the feedback — we hear you.");
        assertThat(template.getUseCount()).isEqualTo(4);
        verify(templateRepository).save(template);
    }

    @Test
    void useTemplate_doesNotMutateOrSaveWhenOwnedByAnotherUser() {
        ReplyTemplate template = ownedTemplate();
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.useTemplate(OTHER_USER_ID, TEMPLATE_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");

        assertThat(template.getUseCount()).isEqualTo(3);
        verify(templateRepository, never()).save(any());
    }

    @Test
    void updateTemplate_rejectsWhenOwnedByAnotherUser() {
        ReplyTemplate template = ownedTemplate();
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));

        UpdateReplyTemplateRequest request = new UpdateReplyTemplateRequest("Hacked", "Malicious body", "rude");

        assertThatThrownBy(() -> service.updateTemplate(OTHER_USER_ID, TEMPLATE_ID, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");

        assertThat(template.getName()).isEqualTo("Apology");
        assertThat(template.getBody()).isEqualTo("Thanks for the feedback — we hear you.");
        verify(templateRepository, never()).save(any());
    }

    @Test
    void deleteTemplate_rejectsWhenOwnedByAnotherUser() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(ownedTemplate()));

        assertThatThrownBy(() -> service.deleteTemplate(OTHER_USER_ID, TEMPLATE_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");

        verify(templateRepository, never()).delete(any());
    }

    @Test
    void deleteTemplate_deletesForOwner() {
        ReplyTemplate template = ownedTemplate();
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));

        service.deleteTemplate(OWNER_ID, TEMPLATE_ID);

        verify(templateRepository).delete(template);
    }
}
