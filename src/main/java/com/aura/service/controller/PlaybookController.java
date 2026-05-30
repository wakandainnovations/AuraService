package com.aura.service.controller;

import com.aura.service.dto.ClonePlaybookRequest;
import com.aura.service.dto.PlaybookResponse;
import com.aura.service.dto.UpdatePlaybookRequest;
import com.aura.service.entity.User;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.PlaybookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/playbooks")
@RequiredArgsConstructor
public class PlaybookController {

    private final PlaybookService playbookService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<PlaybookResponse>> list(
            @RequestParam(value = "entityId", required = false) Long entityId,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "favorite", required = false) Boolean favorite
    ) {
        return ResponseEntity.ok(playbookService.list(entityId, tag, favorite));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlaybookResponse> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdatePlaybookRequest request
    ) {
        return ResponseEntity.ok(playbookService.update(id, request));
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<PlaybookResponse> clone(
            @PathVariable("id") Long id,
            @RequestBody(required = false) ClonePlaybookRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Long userId = requireUserId(principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(playbookService.clone(userId, id, request));
    }

    private Long requireUserId(UserDetails principal) {
        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new RuntimeException(
                        "Authenticated user not found: " + principal.getUsername()));
        return user.getId();
    }
}
