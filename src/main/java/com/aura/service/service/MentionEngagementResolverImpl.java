package com.aura.service.service;

import com.aura.service.enums.Platform;
import com.aura.service.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MentionEngagementResolverImpl implements MentionEngagementResolver {

    private final MentionRepository mentionRepository;

    @Override
    public Map<String, long[]> resolve(Map<Platform, List<String>> postIdsByPlatform) {
        Map<String, long[]> engagementByPostId = new HashMap<>();
        for (Map.Entry<Platform, List<String>> entry : postIdsByPlatform.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            for (Object[] row : fetchEngagementRows(entry.getKey(), entry.getValue())) {
                engagementByPostId.put(String.valueOf(row[0]), new long[]{toLong(row[1]), toLong(row[2])});
            }
        }
        return engagementByPostId;
    }

    private List<Object[]> fetchEngagementRows(Platform platform, List<String> postIds) {
        return switch (platform) {
            case X -> mentionRepository.findXPostEngagement(postIds);
            case YOUTUBE -> mentionRepository.findYoutubeCommentEngagement(postIds);
            case REDDIT -> mentionRepository.findRedditPostEngagement(postIds);
            case INSTAGRAM -> mentionRepository.findInstagramPostEngagement(postIds);
        };
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
