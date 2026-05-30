package com.aura.service.controller;

import com.aura.service.dto.CreateReplyTemplateRequest;
import com.aura.service.dto.ReplyTemplateResponse;
import com.aura.service.dto.UpdateReplyTemplateRequest;
import com.aura.service.dto.UseTemplateResponse;
import com.aura.service.entity.User;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.ReplyTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class ReplyTemplateController {

    private final ReplyTemplateService templateService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<ReplyTemplateResponse> create(
            @Valid @RequestBody CreateReplyTemplateRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Long userId = requireUserId(principal);
        return ResponseEntity.ok(templateService.createTemplate(userId, request));
    }

    @GetMapping
    public ResponseEntity<List<ReplyTemplateResponse>> list(
            @AuthenticationPrincipal UserDetails principal
    ) {
        Long userId = requireUserId(principal);
        return ResponseEntity.ok(templateService.getTemplates(userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ReplyTemplateResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateReplyTemplateRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Long userId = requireUserId(principal);
        return ResponseEntity.ok(templateService.updateTemplate(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Long userId = requireUserId(principal);
        templateService.deleteTemplate(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/use")
    public ResponseEntity<UseTemplateResponse> use(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Long userId = requireUserId(principal);
        String body = templateService.useTemplate(userId, id);
        return ResponseEntity.ok(new UseTemplateResponse(body));
    }

    private Long requireUserId(UserDetails principal) {
        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new RuntimeException(
                        "Authenticated user not found: " + principal.getUsername()));
        return user.getId();
    }
}
