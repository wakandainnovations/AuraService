package com.aura.service.controller;

import com.aura.service.dto.DraftReplyRequest;
import com.aura.service.dto.DraftReplyResponse;
import com.aura.service.dto.EscalateCrisisResponse;
import com.aura.service.dto.MentionActionLogEntry;
import com.aura.service.dto.MentionResponse;
import com.aura.service.dto.MobilizeAlliesResponse;
import com.aura.service.dto.OverrideCategoryRequest;
import com.aura.service.dto.OverrideCategoryResponse;
import com.aura.service.dto.OverrideReviewAspectRequest;
import com.aura.service.dto.OverrideReviewAspectResponse;
import com.aura.service.dto.PostReplyRequest;
import com.aura.service.dto.PostReplyResponse;
import com.aura.service.entity.AuthorTypeOverride;
import com.aura.service.entity.CrisisPlan;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.MobilizeAction;
import com.aura.service.entity.ReplyDraft;
import com.aura.service.entity.ReplyTemplate;
import com.aura.service.entity.ReviewAspectOverride;
import com.aura.service.entity.TopicCategoryOverride;
import com.aura.service.entity.User;
import com.aura.service.repository.AuthorTypeOverrideRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.MobilizeActionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.repository.ReviewAspectOverrideRepository;
import com.aura.service.repository.TopicCategoryOverrideRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.ImpressionsResolver;
import com.aura.service.service.LLMService;
import com.aura.service.service.MobilizeAlliesService;
import com.aura.service.service.ReplyTemplateService;
import com.aura.service.service.ReviewAspectBreakdownService;
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
    private final ReviewAspectOverrideRepository reviewAspectOverrideRepository;
    private final TopicCategoryOverrideRepository topicCategoryOverrideRepository;
    private final AuthorTypeOverrideRepository authorTypeOverrideRepository;
    private final UserRepository userRepository;
    private final MobilizeAlliesService mobilizeAlliesService;
    private final ReplyTemplateService replyTemplateService;
    private final ImpressionsResolver impressionsResolver;
    private final EntityAccessService entityAccessService;

    @Value("${llm.prompt.generate.reply}")
    private String generateReplyPrompt;

    @Value("${llm.prompt.generate.reply.from.template}")
    private String generateReplyFromTemplatePrompt;

    @Value("${llm.prompt.generate.crisis.plan}")
    private String crisisPlanPromptTemplate;

    @GetMapping
    public ResponseEntity<List<MentionActionLogEntry>> listActions(
            @PathVariable("mentionId") Long mentionId,
            @RequestParam(required = false) Long ownerId
    ) {
        // Gate first: a non-admin passing ownerId is rejected (403) before the mention is even looked up.
        entityAccessService.requireAdminToScopeByOwner(ownerId);
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return ResponseEntity.notFound().build();
        }
        // Admins scoped to a user (ownerId) only reach that user's mentions; otherwise normal ownership.
        entityAccessService.assertMentionAccessible(mention, ownerId);

        // Viewing a mention's action panel is a strong signal the user may mobilize allies
        // next. Warm the (expensive) ally cache in the background so that click is a cache hit.
        mobilizeAlliesService.warm(mentionId);

        List<ReplyDraft> drafts = replyDraftRepository.findByMentionId(mentionId);
        List<CrisisPlan> plans = crisisPlanRepository.findByMentionId(mentionId);
        List<MobilizeAction> mobilizes = mobilizeActionRepository.findByMentionId(mentionId);
        List<ReviewAspectOverride> reviewAspectOverrides = reviewAspectOverrideRepository.findByMentionId(mentionId);
        List<TopicCategoryOverride> topicCategoryOverrides = topicCategoryOverrideRepository.findByMentionId(mentionId);
        List<AuthorTypeOverride> authorTypeOverrides = authorTypeOverrideRepository.findByMentionId(mentionId);

        Set<Long> userIds = new HashSet<>();
        for (ReplyDraft d : drafts) userIds.add(d.getUserId());
        for (CrisisPlan p : plans) userIds.add(p.getCreatedBy());
        for (MobilizeAction m : mobilizes) userIds.add(m.getUserId());
        for (ReviewAspectOverride o : reviewAspectOverrides) userIds.add(o.getUserId());
        for (TopicCategoryOverride o : topicCategoryOverrides) userIds.add(o.getUserId());
        for (AuthorTypeOverride o : authorTypeOverrides) userIds.add(o.getUserId());

        Map<Long, String> usernames = new java.util.HashMap<>();
        for (User u : userRepository.findAllById(userIds)) {
            usernames.put(u.getId(), u.getUsername());
        }

        List<MentionActionLogEntry> entries = new ArrayList<>(
                drafts.size() + plans.size() + mobilizes.size() + reviewAspectOverrides.size()
                        + topicCategoryOverrides.size() + authorTypeOverrides.size());
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
        for (ReviewAspectOverride o : reviewAspectOverrides) {
            entries.add(MentionActionLogEntry.builder()
                    .type(MentionActionLogEntry.Type.REVIEW_ASPECT_OVERRIDE)
                    .id(o.getId())
                    .actor(usernames.get(o.getUserId()))
                    .createdAt(o.getCreatedAt())
                    .previousCategory(o.getPreviousCategory())
                    .newCategory(o.getNewCategory())
                    .reason(o.getReason())
                    .build());
        }
        for (TopicCategoryOverride o : topicCategoryOverrides) {
            entries.add(MentionActionLogEntry.builder()
                    .type(MentionActionLogEntry.Type.TOPIC_CATEGORY_OVERRIDE)
                    .id(o.getId())
                    .actor(usernames.get(o.getUserId()))
                    .createdAt(o.getCreatedAt())
                    .previousCategoryValue(o.getPreviousCategory())
                    .newCategoryValue(o.getNewCategory())
                    .reason(o.getReason())
                    .build());
        }
        for (AuthorTypeOverride o : authorTypeOverrides) {
            entries.add(MentionActionLogEntry.builder()
                    .type(MentionActionLogEntry.Type.AUTHOR_TYPE_OVERRIDE)
                    .id(o.getId())
                    .actor(usernames.get(o.getUserId()))
                    .createdAt(o.getCreatedAt())
                    .previousCategoryValue(o.getPreviousCategory())
                    .newCategoryValue(o.getNewCategory())
                    .reason(o.getReason())
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
        ManagedEntity entity = entityAccessService.assertMentionAccessible(mention);
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
        entityAccessService.assertMentionAccessible(mention);

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
        ManagedEntity entity = entityAccessService.assertMentionAccessible(mention);

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
        ManagedEntity entity = entityAccessService.assertMentionAccessible(mention);
        User user = requireUser(principal);

        MobilizeAlliesResponse response = mobilizeAlliesService.getOrComputeAllies(mention);
        recordMobilize(mention, entity, user, response.getAllies().size());
        return ResponseEntity.ok(response);
    }

    /**
     * Human correction of {@link Mention#getReviewAspectCategory()} — the fix for a misclassification
     * spotted via the drill-down filters on {@code GET /api/dashboard/{entityId}/mentions}. Unlike
     * {@link ReviewAspectBreakdownService}'s background sweep, this always overwrites the current
     * value (including an LLM-assigned one), and every override is recorded so the correction is
     * itself auditable via {@link #listActions}.
     */
    @PostMapping("/override-review-aspect")
    public ResponseEntity<OverrideReviewAspectResponse> overrideReviewAspect(
            @PathVariable("mentionId") Long mentionId,
            @Valid @RequestBody OverrideReviewAspectRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return ResponseEntity.notFound().build();
        }
        ManagedEntity entity = entityAccessService.assertMentionAccessible(mention);
        User user = requireUser(principal);

        var previousCategory = mention.getReviewAspectCategory();
        mention.setReviewAspectCategory(request.getCategory());
        mention = mentionRepository.save(mention);

        Instant createdAt = Instant.now();
        ReviewAspectOverride override = reviewAspectOverrideRepository.save(ReviewAspectOverride.builder()
                .mentionId(mention.getId())
                .entityId(entity.getId())
                .userId(user.getId())
                .previousCategory(previousCategory)
                .newCategory(request.getCategory())
                .reason(request.getReason())
                .createdAt(createdAt)
                .build());

        return ResponseEntity.ok(new OverrideReviewAspectResponse(
                toMentionResponse(mention),
                override.getId(),
                previousCategory,
                request.getCategory(),
                createdAt
        ));
    }

    /**
     * Human correction of a mention's {@code topic_category}. Unlike {@link #overrideReviewAspect},
     * this never writes to {@code x_posts}/{@code youtube_comments}/{@code reddit_posts}/
     * {@code instagram_posts} — those are populated by an ingestion pipeline outside this codebase.
     * Instead it appends a {@link TopicCategoryOverride} row; every read path that reports
     * {@code topic_category} (this mention's own current value, the topic-category breakdown, and
     * the {@code topicCategory} drill-down filter) resolves the latest override ahead of the raw
     * upstream column. See {@link TopicCategoryOverride} for why.
     */
    @PostMapping("/override-topic-category")
    public ResponseEntity<OverrideCategoryResponse> overrideTopicCategory(
            @PathVariable("mentionId") Long mentionId,
            @Valid @RequestBody OverrideCategoryRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return ResponseEntity.notFound().build();
        }
        ManagedEntity entity = entityAccessService.assertMentionAccessible(mention);
        User user = requireUser(principal);

        String previousCategory = mentionRepository.findCurrentTopicCategory(mentionId);
        Instant createdAt = Instant.now();
        TopicCategoryOverride override = topicCategoryOverrideRepository.save(TopicCategoryOverride.builder()
                .mentionId(mention.getId())
                .entityId(entity.getId())
                .userId(user.getId())
                .previousCategory(previousCategory)
                .newCategory(request.getCategory())
                .reason(request.getReason())
                .createdAt(createdAt)
                .build());

        return ResponseEntity.ok(new OverrideCategoryResponse(
                toMentionResponse(mention),
                override.getId(),
                previousCategory,
                request.getCategory(),
                createdAt
        ));
    }

    /**
     * Human correction of a mention's {@code author_type}. Same append-only overlay design as
     * {@link #overrideTopicCategory} — see {@link AuthorTypeOverride}.
     */
    @PostMapping("/override-author-type")
    public ResponseEntity<OverrideCategoryResponse> overrideAuthorType(
            @PathVariable("mentionId") Long mentionId,
            @Valid @RequestBody OverrideCategoryRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return ResponseEntity.notFound().build();
        }
        ManagedEntity entity = entityAccessService.assertMentionAccessible(mention);
        User user = requireUser(principal);

        String previousCategory = mentionRepository.findCurrentAuthorType(mentionId);
        Instant createdAt = Instant.now();
        AuthorTypeOverride override = authorTypeOverrideRepository.save(AuthorTypeOverride.builder()
                .mentionId(mention.getId())
                .entityId(entity.getId())
                .userId(user.getId())
                .previousCategory(previousCategory)
                .newCategory(request.getCategory())
                .reason(request.getReason())
                .createdAt(createdAt)
                .build());

        return ResponseEntity.ok(new OverrideCategoryResponse(
                toMentionResponse(mention),
                override.getId(),
                previousCategory,
                request.getCategory(),
                createdAt
        ));
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
                mention.getPrimaryManagedEntity().getId(),
                mention.getPlatform(),
                mention.getPostId(),
                mention.getContent(),
                mention.getAuthor(),
                mention.getPostDate(),
                mention.getSentiment(),
                mention.getPermalink(),
                mention.getSentimentScore(),
                impressionsResolver.resolveForMention(mention)
        );
    }
}
