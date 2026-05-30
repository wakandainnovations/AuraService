package com.aura.service.service;

import com.aura.service.dto.CreateReplyTemplateRequest;
import com.aura.service.dto.ReplyTemplateResponse;
import com.aura.service.dto.UpdateReplyTemplateRequest;
import com.aura.service.entity.ReplyTemplate;
import com.aura.service.repository.ReplyTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReplyTemplateService {

    private final ReplyTemplateRepository templateRepository;

    @Transactional
    public ReplyTemplateResponse createTemplate(Long userId, CreateReplyTemplateRequest request) {
        ReplyTemplate template = ReplyTemplate.builder()
                .userId(userId)
                .name(request.getName())
                .body(request.getBody())
                .tone(request.getTone())
                .useCount(0)
                .createdAt(Instant.now())
                .build();
        template = templateRepository.save(template);
        return mapToResponse(template);
    }

    public List<ReplyTemplateResponse> getTemplates(Long userId) {
        return templateRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReplyTemplateResponse updateTemplate(Long userId, Long id, UpdateReplyTemplateRequest request) {
        ReplyTemplate template = requireOwnedTemplate(userId, id);
        template.setName(request.getName());
        template.setBody(request.getBody());
        template.setTone(request.getTone());
        template = templateRepository.save(template);
        return mapToResponse(template);
    }

    @Transactional
    public void deleteTemplate(Long userId, Long id) {
        ReplyTemplate template = requireOwnedTemplate(userId, id);
        templateRepository.delete(template);
    }

    @Transactional
    public String useTemplate(Long userId, Long id) {
        ReplyTemplate template = requireOwnedTemplate(userId, id);
        template.setUseCount(template.getUseCount() + 1);
        template = templateRepository.save(template);
        return template.getBody();
    }

    public ReplyTemplate requireOwnedTemplate(Long userId, Long id) {
        ReplyTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Reply template not found with id: " + id));
        if (!template.getUserId().equals(userId)) {
            throw new RuntimeException("Reply template not found with id: " + id);
        }
        return template;
    }

    private ReplyTemplateResponse mapToResponse(ReplyTemplate template) {
        return new ReplyTemplateResponse(
                template.getId(),
                template.getName(),
                template.getBody(),
                template.getTone(),
                template.getUseCount(),
                template.getCreatedAt());
    }
}
