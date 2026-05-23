package com.aura.service.controller;

import com.aura.service.dto.AllyRecommendation;
import com.aura.service.dto.DraftReplyResponse;
import com.aura.service.dto.EscalateCrisisResponse;
import com.aura.service.dto.MentionActionLogEntry;
import com.aura.service.dto.MentionResponse;
import com.aura.service.dto.MobilizeAlliesResponse;
import com.aura.service.dto.PostReplyRequest;
import com.aura.service.dto.PostReplyResponse;
import com.aura.service.entity.CrisisPlan;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.MobilizeAction;
import com.aura.service.entity.ReplyDraft;
import com.aura.service.entity.User;
import com.aura.service.enums.Sentiment;
import com.aura.service.proxy.TtlCache;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.MobilizeActionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.LLMService;
import com.aura.service.service.SocialMediaService;
import com.aura.service.service.TopSpreaderLookupService;
import com.aura.service.service.TopSpreaderLookupService.SpreaderProfile;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/mentions/{mentionId}/actions")
@RequiredArgsConstructor
public class MentionActionController {

    static final int ALLY_LIMIT = 10;
    static final Duration ALLY_CACHE_TTL = Duration.ofMinutes(5);

    private final LLMService llmService;
    private final SocialMediaService socialMediaService;
    private final MentionRepository mentionRepository;
    private final ReplyDraftRepository replyDraftRepository;
    private final CrisisPlanRepository crisisPlanRepository;
    private final MobilizeActionRepository mobilizeActionRepository;
    private final UserRepository userRepository;
    private final TopSpreaderLookupService spreaderLookup;

    private final TtlCache<MobilizeAlliesResponse> allyCache = new TtlCache<>(1024);

    @Value("${llm.prompt.generate.reply}")
    private String generateReplyPrompt;

    @Value("${llm.prompt.generate.crisis.plan}")
    private String crisisPlanPromptTemplate;

    @Value("${llm.prompt.generate.ally.dm}")
    private String allyDmPromptTemplate;

    @GetMapping
    public ResponseEntity<List<MentionActionLogEntry>> listActions(
            @PathVariable("mentionId") Long mentionId
    ) {
        if (!mentionRepository.existsById(mentionId)) {
            return ResponseEntity.notFound().build();
        }

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
            @AuthenticationPrincipal UserDetails principal
    ) {
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return ResponseEntity.notFound().build();
        }
        ManagedEntity entity = mention.getManagedEntity();

        String prompt = generateReplyPrompt
                .replace("[Managed Entity]", entity.getName())
                .replace("[Paste the user's post here]", mention.getContent())
                .replace("[Positive / Negative / Neutral]", mention.getSentiment().name());

        String generated = llmService.generateReply(prompt);
        int firstQuote = generated.indexOf('"');
        int lastQuote = generated.lastIndexOf('"');
        if (firstQuote != -1 && lastQuote != -1 && firstQuote != lastQuote) {
            generated = generated.substring(firstQuote + 1, lastQuote);
        }

        User user = requireUser(principal);
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
        ManagedEntity entity = mention.getManagedEntity();
        User user = requireUser(principal);

        String cacheKey = entity.getId() + ":" + mention.getId();
        MobilizeAlliesResponse cached = allyCache.get(cacheKey);
        if (cached != null) {
            recordMobilize(mention, entity, user, cached.getAllies().size());
            return ResponseEntity.ok(cached);
        }

        List<String> keywords = new ArrayList<>();
        if (entity.getKeywords() != null) {
            for (EntityKeyword ek : entity.getKeywords()) {
                if (ek != null && ek.getKeyword() != null && !ek.getKeyword().isBlank()) {
                    keywords.add(ek.getKeyword());
                }
            }
        }

        Map<String, SpreaderProfile> candidates = fetchSpreaderProfiles(keywords);
        if (candidates.isEmpty()) {
            MobilizeAlliesResponse empty = new MobilizeAlliesResponse(toMentionResponse(mention), List.of());
            allyCache.put(cacheKey, empty, ALLY_CACHE_TTL.toNanos());
            recordMobilize(mention, entity, user, 0);
            return ResponseEntity.ok(empty);
        }

        Map<String, Long> positiveCounts = filterPredominantlyPositive(entity.getId(), candidates.keySet());

        List<SpreaderProfile> ranked = candidates.values().stream()
                .filter(p -> positiveCounts.containsKey(p.globalUserId()))
                .sorted(Comparator
                        .comparingLong((SpreaderProfile p) ->
                                positiveCounts.getOrDefault(p.globalUserId(), 0L)).reversed()
                        .thenComparing(p -> tierRank(p.influenceTier()))
                        .thenComparing(SpreaderProfile::globalUserId))
                .limit(ALLY_LIMIT)
                .toList();

        List<AllyRecommendation> allies = new ArrayList<>(ranked.size());
        for (SpreaderProfile p : ranked) {
            String dm = generateAllyDm(entity.getName(), mention.getContent(), p);
            allies.add(new AllyRecommendation(
                    p.globalUserId(),
                    p.primaryPlatform(),
                    p.influenceTier(),
                    dm
            ));
        }

        MobilizeAlliesResponse response = new MobilizeAlliesResponse(toMentionResponse(mention), allies);
        allyCache.put(cacheKey, response, ALLY_CACHE_TTL.toNanos());
        recordMobilize(mention, entity, user, allies.size());
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

    private Map<String, SpreaderProfile> fetchSpreaderProfiles(List<String> keywords) {
        if (keywords.isEmpty()) {
            return Map.of();
        }
        List<List<SpreaderProfile>> perKeyword = Flux.fromIterable(keywords)
                .flatMap(kw -> Mono.fromCallable(() -> spreaderLookup.getSpreaderProfiles(kw))
                        .subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .blockOptional()
                .orElse(List.of());

        Map<String, SpreaderProfile> deduped = new LinkedHashMap<>();
        for (List<SpreaderProfile> profiles : perKeyword) {
            for (SpreaderProfile p : profiles) {
                if (p.globalUserId() == null || p.globalUserId().isBlank()) {
                    continue;
                }
                deduped.merge(p.globalUserId(), p, (existing, incoming) -> new SpreaderProfile(
                        existing.globalUserId(),
                        existing.primaryPlatform() != null ? existing.primaryPlatform() : incoming.primaryPlatform(),
                        existing.influenceTier() != null ? existing.influenceTier() : incoming.influenceTier()
                ));
            }
        }
        return deduped;
    }

    private Map<String, Long> filterPredominantlyPositive(Long entityId, java.util.Set<String> authors) {
        if (authors.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = mentionRepository.countSentimentByAuthorsForEntity(entityId, authors);
        Map<String, EnumMap<Sentiment, Long>> byAuthor = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String author = (String) row[0];
            Sentiment sentiment = (Sentiment) row[1];
            long count = ((Number) row[2]).longValue();
            byAuthor.computeIfAbsent(author, k -> new EnumMap<>(Sentiment.class))
                    .merge(sentiment, count, Long::sum);
        }
        Map<String, Long> positive = new LinkedHashMap<>();
        for (Map.Entry<String, EnumMap<Sentiment, Long>> e : byAuthor.entrySet()) {
            EnumMap<Sentiment, Long> counts = e.getValue();
            long pos = counts.getOrDefault(Sentiment.POSITIVE, 0L);
            long neg = counts.getOrDefault(Sentiment.NEGATIVE, 0L);
            long neu = counts.getOrDefault(Sentiment.NEUTRAL, 0L);
            if (pos > 0 && pos > neg && pos >= neu) {
                positive.put(e.getKey(), pos);
            }
        }
        return positive;
    }

    private String generateAllyDm(String entityName, String mentionContent, SpreaderProfile profile) {
        String prompt = allyDmPromptTemplate
                .replace("[Managed Entity]", nullSafe(entityName))
                .replace("[Ally Handle]", nullSafe(profile.globalUserId()))
                .replace("[Ally Platform]", nullSafe(profile.primaryPlatform()))
                .replace("[Ally Tier]", nullSafe(profile.influenceTier()))
                .replace("[Mention Content]", nullSafe(mentionContent));

        String generated = llmService.generateReply(prompt);
        if (generated == null) {
            return "";
        }
        int firstQuote = generated.indexOf('"');
        int lastQuote = generated.lastIndexOf('"');
        if (firstQuote != -1 && lastQuote != -1 && firstQuote != lastQuote) {
            generated = generated.substring(firstQuote + 1, lastQuote);
        }
        return generated;
    }

    private static int tierRank(String tier) {
        if (tier == null) return Integer.MAX_VALUE;
        String t = tier.toUpperCase();
        return switch (t) {
            case "TIER_1", "TIER1", "T1" -> 1;
            case "TIER_2", "TIER2", "T2" -> 2;
            case "TIER_3", "TIER3", "T3" -> 3;
            case "TIER_4", "TIER4", "T4" -> 4;
            default -> Integer.MAX_VALUE - 1;
        };
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
