package com.aura.service.controller;

import com.aura.service.dto.GenerateReplyResponse;
import com.aura.service.dto.RespondRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.repository.MentionRepository;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.LLMService;
import com.aura.service.service.SocialMediaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/interact")
@RequiredArgsConstructor
public class InteractionController {

    private final LLMService llmService;
    private final SocialMediaService socialMediaService;
    private final MentionRepository mentionRepository;
    private final EntityAccessService entityAccessService;

    @Value("${llm.prompt.generate.reply}")
    private String generateReplyPrompt;

    @GetMapping("/generate-reply/{post_id}")
    public ResponseEntity<GenerateReplyResponse> generateReply(@PathVariable("post_id") Long postId) {
        Optional<Mention> mentionOptional = mentionRepository.findById(postId);
        if (mentionOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Mention mention = mentionOptional.get();

        // Only the owner of one of the mention's entities may generate a reply for it (404s otherwise).
        ManagedEntity managedEntity = entityAccessService.assertMentionAccessible(mention);

        String prompt = generateReplyPrompt
                .replace("[Managed Entity]", managedEntity.getName())
                .replace("[Paste the user's post here]", mention.getContent())
                .replace("[Positive / Negative / Neutral]", mention.getSentiment().name());

        String generatedReply = llmService.generateReply(prompt);
        int firstQuote = generatedReply.indexOf('"');
        int lastQuote = generatedReply.lastIndexOf('"');
        if (firstQuote != -1 && lastQuote != -1 && firstQuote != lastQuote) {
            generatedReply = generatedReply.substring(firstQuote + 1, lastQuote);
        }
        return ResponseEntity.ok(new GenerateReplyResponse(generatedReply));
    }

    @PostMapping("/respond")
    public ResponseEntity<String> respond(@Valid @RequestBody RespondRequest request) {
        String result = socialMediaService.postReply(
                request.getPlatform(),
                request.getPostIdToReplyTo(),
                request.getReplyText()
        );
        return ResponseEntity.ok(result);
    }
}
