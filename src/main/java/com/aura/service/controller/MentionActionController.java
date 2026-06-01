package com.aura.service.controller;

import com.aura.service.dto.DraftReplyRequest;
import com.aura.service.dto.DraftReplyResponse;
import com.aura.service.dto.EscalateCrisisResponse;
import com.aura.service.dto.MentionActionLogEntry;
import com.aura.service.dto.MentionResponse;
import com.aura.service.dto.MobilizeAlliesResponse;
import com.aura.service.dto.PostReplyRequest;
import com.aura.service.dto.PostReplyResponse;
import com.aura.service.entity.CrisisPlan;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.MobilizeAction;
import com.aura.service.entity.ReplyDraft;
import com.aura.service.entity.ReplyTemplate;
import com.aura.service.entity.User;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.MobilizeActionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.LLMService;
import com.aura.service.service.MobilizeAlliesService;
import com.aura.service.service.ReplyTemplateService;
import com.aura.service.service.SocialMediaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/mentions/{mentionId}/actions")
@RequiredArgsConstructor
public class MentionActionController {

    private final LLMService llmService;
    private final SocialMediaService socialMediaService;
    private final MentionRepository mentionRepository;
    private final ReplyDraftRepository replyDraftRepository;
    private final CrisisPlanRepository crisisPlanRepository;
    private final MobilizeActionRepository mobilizeActionRepository;
    private final UserRepository userRepository;
    private final MobilizeAlliesService mobilizeAlliesService;
    private final ReplyTemplateService replyTemplateService;

    @Value("${llm.prompt.generate.reply}")
    private String generateReplyPrompt;

    @Value("${llm.prompt.generate.reply.from.template}")
    private String generateReplyFromTemplatePrompt;

    @Value("${llm.prompt.generate.crisis.plan}")
    private String crisisPlanPromptTemplate;

    @GetMapping
    public ResponseEntity<List<MentionActionLogEntry>> listActions(
            @PathVariable("mentionId") Long mentionId
    ) {
        if (!mentionRepository.existsById(mentionId)) {
            return ResponseEntity.notFound().build();
        }

        // Viewing a mention's action panel is a strong signal the user may mobilize allies
        // next. Warm the (expensive) ally cache in the background so that click is a cache hit.
        mobilizeAlliesService.warm(mentionId);

        List<ReplyDraft> drafts = replyDraftRepository.findByMentionId(mentionId);
        List<CrisisPlan> plans = crisisPlanRepository.findByMentionId(mentionId);
        List<MobilizeAction> mobilizes = mobilizeActionRepository.findByMentionId(mentionId);

        Set<Long> userIds = new HashSet<>();
        for (ReplyDraft d : drafts) userIds.add(d.getUserId());
        for (CrisisPlan p : plans) userIds.add(p.getCreatedBy());
        for (MobilizeAction m : mobilizes) userIds.add(m.getUserId());

        Map<Long, String> usernames = new java.util.HashMap<>();
        for (User u : userRepository.findAllById(userIds)) {
            usernames.put(u.getId(), u.getUsername());
        }

        List<MentionActionLogEntry> entries = new ArrayList<>(
                drafts.size() + plans.size() + mobilizes.size());
        for (ReplyDraft d : drafts) {
            entries.add(MentionActionLogEntry.builder()
                    .type(MentionActionLogEntry.Type.REPLY_DRAFT)
                    .id(d.getId())
                    .actor(usernames.get(d.getUserId()))
                    .createdAt(d.getCreatedAt())
                    .draftStatus(d.getStatus())
                    .text(d.getText())
                    .postedAt(d.getPostedAt())
                    .build());
        }
        for (CrisisPlan p : plans) {
            entries.add(MentionActionLogEntry.builder()
                    .type(MentionActionLogEntry.Type.CRISIS_PLAN)
                    .id(p.getId())
                    .actor(usernames.get(p.getCreatedBy()))
                    .createdAt(p.getCreatedAt())
                    .planText(p.getPlanText())
                    .build());
        }
        for (MobilizeAction m : mobilizes) {
            entries.add(MentionActionLogEntry.builder()
                    .type(MentionActionLogEntry.Type.MOBILIZE)
                    .id(m.getId())
                    .actor(usernames.get(m.getUserId()))
                    .createdAt(m.getCreatedAt())
                    .allyCount(m.getAllyCount())
                    .build());
        }

        entries.sort(Comparator.comparing(
                MentionActionLogEntry::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return ResponseEntity.ok(entries);
    }

    @PostMapping("/draft-reply")
    public ResponseEntity<DraftReplyResponse> draftReply(
            @PathVariable("mentionId") Long mentionId,
            @RequestBody(required = false) DraftReplyRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return ResponseEntity.notFound().build();
        }
        ManagedEntity entity = mention.getManagedEntity();
        User user = requireUser(principal);

        String prompt;
        if (request != null && request.getTemplateId() != null) {
            ReplyTemplate template = replyTemplateService.requireOwnedTemplate(user.getId(), request.getTemplateId());
            prompt = generateReplyFromTemplatePrompt
                    .replace("[Managed Entity]", entity.getName())
                    .replace("[Template Tone]", nullSafe(template.getTone()))
                    .replace("[Template Body]", nullSafe(template.getBody()))
                    .replace("[Paste the user's post here]", mention.getContent())
                    .replace("[Positive / Negative / Neutral]", mention.getSentiment().name());
        } else {
            prompt = generateReplyPrompt
                    .replace("[Managed Entity]", entity.getName())
                    .replace("[Paste the user's post here]", mention.getContent())
                    .replace("[Positive / Negative / Neutral]", mention.getSentiment().name());
        }

        String generated = llmService.generateReply(prompt);
        int firstQuote = generated.indexOf('"');
        int lastQuote = generated.lastIndexOf('"');
        if (firstQuote != -1 && lastQuote != -1 && firstQuote != lastQuote) {
            generated = generated.substring(firstQuote + 1, lastQuote);
        }

        ReplyDraft draft = ReplyDraft.builder()
                .mentionId(mention.getId())
                .userId(user.getId())
                .text(generated)
                .status(ReplyDraft.Status.DRAFT)
                .createdAt(Instant.now())
                .build();
        draft = replyDraftRepository.save(draft);

        return ResponseEntity.ok(new DraftReplyResponse(
                toMentionResponse(mention),
                draft.getId(),
                generated
        ));
    }

    @PostMapping("/post-reply")
    public ResponseEntity<PostReplyResponse> postReply(
            @PathVariable("mentionId") Long mentionId,
            @Valid @RequestBody PostReplyRequest request
    ) {
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return ResponseEntity.notFound().build();
        }

        ReplyDraft draft = replyDraftRepository.findById(request.getDraftId()).orElse(null);
        if (draft == null || !draft.getMentionId().equals(mentionId)) {
            return ResponseEntity.notFound().build();
        }

        String result = socialMediaService.postReply(
                mention.getPlatform(),
                mention.getPostId(),
                draft.getText()
        );

        draft.setStatus(ReplyDraft.Status.POSTED);
        draft.setPostedAt(Instant.now());
        draft = replyDraftRepository.save(draft);

        return ResponseEntity.ok(new PostReplyResponse(
                toMentionResponse(mention),
                draft.getId(),
                draft.getText(),
                draft.getPostedAt(),
                result
        ));
    }

    @PostMapping("/escalate-to-crisis")
    public ResponseEntity<EscalateCrisisResponse> escalateToCrisis(
            @PathVariable("mentionId") Long mentionId,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return ResponseEntity.notFound().build();
        }
        ManagedEntity entity = mention.getManagedEntity();

        String prompt = crisisPlanPromptTemplate
                .replace("[Managed Entity]", entity.getName())
                .replace("[Crisis Description]", mention.getContent());

        String generatedPlan = llmService.generateCrisisPlan(prompt);

        User user = requireUser(principal);
        CrisisPlan plan = CrisisPlan.builder()
                .entityId(entity.getId())
                .mentionId(mention.getId())
                .planText(generatedPlan)
                .createdBy(user.getId())
                .createdAt(Instant.now())
                .build();
        plan = crisisPlanRepository.save(plan);

        return ResponseEntity.ok(new EscalateCrisisResponse(
                toMentionResponse(mention),
                plan.getId(),
                generatedPlan
        ));
    }

    @PostMapping("/mobilize-allies")
    public ResponseEntity<MobilizeAlliesResponse> mobilizeAllies(
            @PathVariable("mentionId") Long mentionId,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return ResponseEntity.notFound().build();
        }
        User user = requireUser(principal);

        MobilizeAlliesResponse response = mobilizeAlliesService.getOrComputeAllies(mention);
        recordMobilize(mention, mention.getManagedEntity(), user, response.getAllies().size());
        return ResponseEntity.ok(response);
    }

    private void recordMobilize(Mention mention, ManagedEntity entity, User user, int allyCount) {
        mobilizeActionRepository.save(MobilizeAction.builder()
                .mentionId(mention.getId())
                .entityId(entity.getId())
                .userId(user.getId())
                .allyCount(allyCount)
                .createdAt(Instant.now())
                .build());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private User requireUser(UserDetails principal) {
        return userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new RuntimeException(
                        "Authenticated user not found: " + principal.getUsername()));
    }

    private MentionResponse toMentionResponse(Mention mention) {
        return new MentionResponse(
                mention.getId(),
                mention.getManagedEntity().getId(),
                mention.getPlatform(),
                mention.getPostId(),
                mention.getContent(),
                mention.getAuthor(),
                mention.getPostDate(),
                mention.getSentiment(),
                mention.getPermalink(),
                mention.getSentimentScore()
        );
    }
}
