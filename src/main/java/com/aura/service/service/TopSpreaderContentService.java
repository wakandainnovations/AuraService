package com.aura.service.service;

import com.aura.service.dto.SpreaderPostContent;
import com.aura.service.dto.TopSpreaderContent;
import com.aura.service.dto.TopSpreaderContentResponse;
import com.aura.service.entity.EntityLanguageSpreaderSnapshot;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import com.aura.service.repository.EntityLanguageSpreaderSnapshotRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.service.TopSpreaderLookupService.SpreaderProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * For an entity's top spreaders (AuraMath's top-50-spreaders identities, cached per (entity, language)
 * in {@link EntityLanguageSpreaderSnapshot} by {@link TopSpreaderLanguageSyncService}), resolves what
 * each spreader has actually posted about this entity - view count, engagement rate, and sentiment per
 * post - by joining the spreader's {@code globalUserId} against {@code mentions.author}. See
 * {@link MentionRepository#findByManagedEntityIdAndAuthorIn} for why that join is a direct string
 * match rather than a fuzzy one: it's the same identity equivalence
 * {@code RecommendedActionCandidateServiceImpl}'s evangelist-mobilization candidate already relies on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopSpreaderContentService {

    private final EntityLanguageSpreaderSnapshotRepository snapshotRepository;
    private final MentionRepository mentionRepository;
    private final ObjectMapper objectMapper;

    public TopSpreaderContentResponse getTopSpreaderContent(
            Long entityId, String language, int spreaderLimit, int postsPerSpreader) {
        List<SpreaderProfile> rankedProfiles = rankedSpreaderProfiles(entityId, language, spreaderLimit);
        if (rankedProfiles.isEmpty()) {
            return new TopSpreaderContentResponse(entityId, language, List.of());
        }

        List<String> authors = rankedProfiles.stream().map(SpreaderProfile::globalUserId).toList();
        List<Mention> mentions = mentionRepository.findByManagedEntityIdAndAuthorIn(entityId, authors);
        Map<Long, PostMetrics> metricsByMentionId = resolvePostMetrics(mentions);

        Map<String, List<Mention>> mentionsByAuthor = new HashMap<>();
        for (Mention mention : mentions) {
            mentionsByAuthor.computeIfAbsent(mention.getAuthor(), k -> new ArrayList<>()).add(mention);
        }

        List<TopSpreaderContent> spreaders = new ArrayList<>(rankedProfiles.size());
        for (SpreaderProfile profile : rankedProfiles) {
            List<Mention> authored = mentionsByAuthor.getOrDefault(profile.globalUserId(), List.of());
            List<SpreaderPostContent> topContent = authored.stream()
                    .map(m -> toPostContent(m, metricsByMentionId.get(m.getId())))
                    .sorted(Comparator.comparing(
                            (SpreaderPostContent p) -> p.views() == null ? -1L : p.views()).reversed())
                    .limit(postsPerSpreader)
                    .toList();
            spreaders.add(new TopSpreaderContent(
                    profile.globalUserId(), profile.profileUrl(), profile.totalViews(), topContent));
        }
        return new TopSpreaderContentResponse(entityId, language, spreaders);
    }

    /**
     * The entity's spreader profiles for {@code language} (or, when {@code language} is blank, deduped
     * across every language the entity is tracked in - the same first-seen dedupe
     * {@code TopSpreaderLanguageSyncService} uses within one language), ranked by AuraMath's
     * {@code totalViews} - the only real reach proxy that endpoint provides - and capped at
     * {@code spreaderLimit}.
     */
    private List<SpreaderProfile> rankedSpreaderProfiles(Long entityId, String language, int spreaderLimit) {
        List<EntityLanguageSpreaderSnapshot> snapshots = (language == null || language.isBlank())
                ? snapshotRepository.findByEntityId(entityId)
                : snapshotRepository.findByEntityIdAndLanguageIgnoreCase(entityId, language)
                        .map(List::of).orElse(List.of());

        Map<String, SpreaderProfile> deduped = new LinkedHashMap<>();
        for (EntityLanguageSpreaderSnapshot snapshot : snapshots) {
            for (SpreaderProfile profile : readSpreaderProfiles(snapshot)) {
                if (profile.globalUserId() != null && !profile.globalUserId().isBlank()) {
                    deduped.putIfAbsent(profile.globalUserId(), profile);
                }
            }
        }
        return deduped.values().stream()
                .sorted(Comparator.comparingLong(SpreaderProfile::totalViews).reversed())
                .limit(spreaderLimit)
                .toList();
    }

    private List<SpreaderProfile> readSpreaderProfiles(EntityLanguageSpreaderSnapshot snapshot) {
        try {
            SpreaderProfile[] profiles = objectMapper.readValue(snapshot.getSpreadersJson(), SpreaderProfile[].class);
            return List.of(profiles);
        } catch (Exception e) {
            log.warn("Failed to parse spreaders_json for snapshot {} (entity {}, language {})",
                    snapshot.getId(), snapshot.getEntityId(), snapshot.getLanguage(), e);
            return List.of();
        }
    }

    private record PostMetrics(Long views, long likes, long comments) {
    }

    /**
     * Batches views + likes/comments resolution per platform across every mention, same "one query per
     * platform, not per post" batching {@link ImpressionsResolver} uses. Views come from
     * {@code findXPostViewsCounts} (X) plus the three proxy queries added alongside it; likes/comments
     * reuse the per-platform engagement queries {@code GraphSyncServiceImpl} already relies on.
     */
    private Map<Long, PostMetrics> resolvePostMetrics(List<Mention> mentions) {
        Map<String, Long> views = new HashMap<>();
        Map<String, long[]> engagement = new HashMap<>();

        for (Platform platform : Platform.values()) {
            List<String> postIds = mentions.stream()
                    .filter(m -> m.getPlatform() == platform)
                    .map(Mention::getPostId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (postIds.isEmpty()) {
                continue;
            }
            switch (platform) {
                case X -> {
                    mentionRepository.findXPostViewsCounts(postIds).forEach(row -> putView(views, row));
                    mentionRepository.findXPostEngagement(postIds).forEach(row -> putEngagement(engagement, row));
                }
                case YOUTUBE -> {
                    mentionRepository.findYoutubePostViews(postIds).forEach(row -> putView(views, row));
                    mentionRepository.findYoutubeCommentEngagement(postIds).forEach(row -> putEngagement(engagement, row));
                }
                case REDDIT -> {
                    mentionRepository.findRedditPostViews(postIds).forEach(row -> putView(views, row));
                    mentionRepository.findRedditPostEngagement(postIds).forEach(row -> putEngagement(engagement, row));
                }
                case INSTAGRAM -> {
                    mentionRepository.findInstagramPostViews(postIds).forEach(row -> putView(views, row));
                    mentionRepository.findInstagramPostEngagement(postIds).forEach(row -> putEngagement(engagement, row));
                }
                default -> log.warn("No view/engagement resolution wired for platform {}", platform);
            }
        }

        Map<Long, PostMetrics> byMentionId = new HashMap<>();
        for (Mention mention : mentions) {
            Long postViews = views.get(mention.getPostId());
            long[] eng = engagement.getOrDefault(mention.getPostId(), new long[]{0L, 0L});
            byMentionId.put(mention.getId(), new PostMetrics(postViews, eng[0], eng[1]));
        }
        return byMentionId;
    }

    private static void putView(Map<String, Long> views, Object[] row) {
        String postId = (String) row[0];
        Number value = (Number) row[1];
        if (value != null) {
            views.put(postId, value.longValue());
        }
    }

    private static void putEngagement(Map<String, long[]> engagement, Object[] row) {
        String postId = (String) row[0];
        Number likes = (Number) row[1];
        Number comments = (Number) row[2];
        engagement.put(postId, new long[]{
                likes == null ? 0L : likes.longValue(),
                comments == null ? 0L : comments.longValue()});
    }

    private static SpreaderPostContent toPostContent(Mention mention, PostMetrics metrics) {
        Long views = metrics == null ? null : metrics.views();
        long likes = metrics == null ? 0L : metrics.likes();
        long comments = metrics == null ? 0L : metrics.comments();
        Double engagementRate = (views == null || views <= 0) ? null : (likes + comments) / (double) views;
        return new SpreaderPostContent(
                mention.getId(), mention.getPlatform(), mention.getPostId(), mention.getContent(),
                mention.getPermalink(), mention.getPostDate(), views, likes, comments,
                engagementRate, mention.getSentiment(), mention.getSentimentScore());
    }
}
