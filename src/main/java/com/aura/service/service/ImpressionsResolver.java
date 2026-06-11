package com.aura.service.service;

import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import com.aura.service.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the impression count of a mention's underlying post from the per-platform
 * ingestion tables. Only X exposes an impression metric (x_posts.views_count); Reddit,
 * Instagram and YouTube tables carry no impression data, so those resolve to "NA".
 */
@Service
@RequiredArgsConstructor
public class ImpressionsResolver {

    public static final String NOT_AVAILABLE = "NA";

    private final MentionRepository mentionRepository;

    /**
     * Resolves impressions for a batch of mentions in a single query, keyed by mention id.
     * Every mention in the input is present in the result, mapped to "NA" when no
     * impression data exists for its platform or post.
     */
    public Map<Long, String> resolveForMentions(List<Mention> mentions) {
        List<String> xPostIds = mentions.stream()
                .filter(m -> m.getPlatform() == Platform.X)
                .map(Mention::getPostId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, String> viewsByPostId = new HashMap<>();
        if (!xPostIds.isEmpty()) {
            for (Object[] row : mentionRepository.findXPostViewsCounts(xPostIds)) {
                String postId = (String) row[0];
                Number views = (Number) row[1];
                if (views != null) {
                    viewsByPostId.put(postId, String.valueOf(views.longValue()));
                }
            }
        }

        Map<Long, String> impressionsByMentionId = new HashMap<>();
        for (Mention mention : mentions) {
            String impressions = mention.getPlatform() == Platform.X
                    ? viewsByPostId.getOrDefault(mention.getPostId(), NOT_AVAILABLE)
                    : NOT_AVAILABLE;
            impressionsByMentionId.put(mention.getId(), impressions);
        }
        return impressionsByMentionId;
    }

    public String resolveForMention(Mention mention) {
        return resolveForMentions(List.of(mention)).get(mention.getId());
    }
}
