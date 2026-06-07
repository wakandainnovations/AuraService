package com.aura.service.controller;

import com.aura.service.service.MentionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mentions")
@RequiredArgsConstructor
public class MentionController {

    private final MentionService mentionService;

    /**
     * Deletes a mention (and its dependent records) outright. Used to purge false-positive mentions
     * that should never have been attributed to an entity.
     */
    @DeleteMapping("/{mentionId}")
    public ResponseEntity<Void> deleteMention(@PathVariable("mentionId") Long mentionId) {
        boolean deleted = mentionService.deleteMention(mentionId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
