package com.aura.service.service;

import com.aura.service.dto.AudienceCohortPatternResponse;
import com.aura.service.dto.AudienceTimingPatternResponse;
import com.aura.service.dto.CohortEngagementStats;
import com.aura.service.dto.DayOfWeekEngagement;
import com.aura.service.dto.HourOfDayEngagement;
import com.aura.service.dto.RecommendedTimeSlot;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.enums.CohortGroupBy;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AudiencePatternServiceImpl implements AudiencePatternService {

    private static final String MOVIE_TYPE = "MOVIE";
    private static final String UNSPECIFIED_COHORT = "Unspecified";
    private static final int TOP_TIME_SLOT_LIMIT = 10;
    private static final long[] ZERO_ENGAGEMENT = {0L, 0L};

    private final ManagedEntityRepository entityRepository;
    private final MentionRepository mentionRepository;
    private final EntityAccessService entityAccessService;
    private final MentionEngagementResolver engagementResolver;

    @Override
    public AudienceTimingPatternResponse getTimingPattern(
            String language, String industry, String movieName,
            Instant from, Instant to, Long requestedOwnerId) {
        if (!StringUtils.hasText(language) && !StringUtils.hasText(industry) && !StringUtils.hasText(movieName)) {
            throw new IllegalArgumentException(
                    "At least one of language, industry, or movieName is required to scope the analysis");
        }

        Long scope = entityAccessService.resolveOwnerScope(requestedOwnerId);
        List<ManagedEntity> movies = entityRepository.findMoviesByFilters(
                MOVIE_TYPE.toLowerCase(), lower(language), lower(industry), lower(movieName), scope);
        if (movies.isEmpty()) {
            throw new ResourceNotFoundException("No movies found matching the given filters");
        }

        List<Long> entityIds = movies.stream().map(ManagedEntity::getId).toList();
        List<Mention> mentions = mentionRepository.findByEntityIdsAndDateRange(
                entityIds, effectiveFrom(from), effectiveTo(to));

        Map<Platform, List<String>> postIdsByPlatform = mentions.stream()
                .filter(m -> m.getPostId() != null)
                .collect(Collectors.groupingBy(Mention::getPlatform,
                        Collectors.mapping(Mention::getPostId, Collectors.toList())));
        Map<String, long[]> engagementByPostId = engagementResolver.resolve(postIdsByPlatform);

        Map<Integer, Bucket> byHour = new TreeMap<>();
        Map<DayOfWeek, Bucket> byDay = new EnumMap<>(DayOfWeek.class);
        Map<TimeSlot, Bucket> bySlot = new LinkedHashMap<>();

        Set<String> uniqueAuthors = new HashSet<>();
        long totalLikes = 0;
        long totalComments = 0;

        for (Mention mention : mentions) {
            OffsetDateTime postTime = mention.getPostDate().atOffset(ZoneOffset.UTC);
            int hour = postTime.getHour();
            DayOfWeek day = postTime.getDayOfWeek();
            String author = mention.getAuthor();

            long[] engagement = engagementByPostId.getOrDefault(mention.getPostId(), ZERO_ENGAGEMENT);
            totalLikes += engagement[0];
            totalComments += engagement[1];
            if (author != null) {
                uniqueAuthors.add(author);
            }

            byHour.computeIfAbsent(hour, h -> new Bucket()).add(author, engagement[0], engagement[1]);
            byDay.computeIfAbsent(day, d -> new Bucket()).add(author, engagement[0], engagement[1]);
            bySlot.computeIfAbsent(new TimeSlot(day, hour), s -> new Bucket())
                    .add(author, engagement[0], engagement[1]);
        }

        List<HourOfDayEngagement> byHourOfDay = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            Bucket bucket = byHour.getOrDefault(hour, new Bucket());
            byHourOfDay.add(new HourOfDayEngagement(hour, bucket.postCount, bucket.authors.size(),
                    bucket.totalLikes, bucket.totalComments, bucket.totalEngagement(), bucket.avgEngagementPerPost()));
        }

        List<DayOfWeekEngagement> byDayOfWeek = new ArrayList<>(7);
        for (DayOfWeek day : DayOfWeek.values()) {
            Bucket bucket = byDay.getOrDefault(day, new Bucket());
            byDayOfWeek.add(new DayOfWeekEngagement(day, bucket.postCount, bucket.authors.size(),
                    bucket.totalLikes, bucket.totalComments, bucket.totalEngagement(), bucket.avgEngagementPerPost()));
        }

        List<RecommendedTimeSlot> topTimeSlots = bySlot.entrySet().stream()
                .filter(e -> e.getValue().totalEngagement() > 0)
                .sorted(Comparator.comparingLong((Map.Entry<TimeSlot, Bucket> e) -> e.getValue().totalEngagement())
                        .reversed())
                .limit(TOP_TIME_SLOT_LIMIT)
                .map(e -> new RecommendedTimeSlot(e.getKey().dayOfWeek(), e.getKey().hour(),
                        e.getValue().postCount, e.getValue().totalEngagement(), e.getValue().avgEngagementPerPost()))
                .toList();

        return new AudienceTimingPatternResponse(
                buildScopeDescription(language, industry, movieName), movies.size(), mentions.size(),
                uniqueAuthors.size(), totalLikes + totalComments, byHourOfDay, byDayOfWeek, topTimeSlots);
    }

    @Override
    public AudienceCohortPatternResponse getCohortPattern(
            CohortGroupBy groupBy, Instant from, Instant to, Long requestedOwnerId) {
        Long scope = entityAccessService.resolveOwnerScope(requestedOwnerId);
        List<ManagedEntity> movies = entityRepository.findMoviesByFilters(
                MOVIE_TYPE.toLowerCase(), null, null, null, scope);
        if (movies.isEmpty()) {
            throw new ResourceNotFoundException("No movies found to analyze");
        }

        Map<Long, ManagedEntity> movieById = movies.stream()
                .collect(Collectors.toMap(ManagedEntity::getId, m -> m));
        Map<String, Set<Long>> movieIdsByCohort = new LinkedHashMap<>();
        for (ManagedEntity movie : movies) {
            movieIdsByCohort.computeIfAbsent(cohortKey(groupBy, movie), k -> new LinkedHashSet<>())
                    .add(movie.getId());
        }

        List<Object[]> rows = mentionRepository.findMentionEngagementInputsForEntities(
                new ArrayList<>(movieById.keySet()), effectiveFrom(from), effectiveTo(to));

        Map<Platform, List<String>> postIdsByPlatform = rows.stream()
                .filter(r -> r[3] != null)
                .collect(Collectors.groupingBy(r -> (Platform) r[2],
                        Collectors.mapping(r -> (String) r[3], Collectors.toList())));
        Map<String, long[]> engagementByPostId = engagementResolver.resolve(postIdsByPlatform);

        Map<String, CohortAccumulator> cohorts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            ManagedEntity movie = movieById.get((Long) row[0]);
            if (movie == null) {
                continue;
            }
            String author = (String) row[1];
            String postId = (String) row[3];
            Short sentimentScore = (Short) row[4];
            Sentiment sentiment = (Sentiment) row[5];
            long[] engagement = engagementByPostId.getOrDefault(postId, ZERO_ENGAGEMENT);

            cohorts.computeIfAbsent(cohortKey(groupBy, movie), CohortAccumulator::new)
                    .add(author, engagement[0], engagement[1], sentimentScore, sentiment);
        }

        List<CohortEngagementStats> cohortStats = movieIdsByCohort.entrySet().stream()
                .map(e -> cohorts.getOrDefault(e.getKey(), new CohortAccumulator(e.getKey()))
                        .toStats(e.getValue().size()))
                .sorted(Comparator.comparingLong(CohortEngagementStats::totalEngagement).reversed())
                .toList();

        return new AudienceCohortPatternResponse(groupBy.name(), cohortStats.size(), cohortStats);
    }

    private String cohortKey(CohortGroupBy groupBy, ManagedEntity movie) {
        String value = groupBy == CohortGroupBy.INDUSTRY ? movie.getIndustry() : movie.getLanguage();
        return StringUtils.hasText(value) ? value : UNSPECIFIED_COHORT;
    }

    private String buildScopeDescription(String language, String industry, String movieName) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(movieName)) {
            parts.add("movie=" + movieName);
        }
        if (StringUtils.hasText(language)) {
            parts.add("language=" + language);
        }
        if (StringUtils.hasText(industry)) {
            parts.add("industry=" + industry);
        }
        return String.join(", ", parts);
    }

    private static Instant effectiveFrom(Instant from) {
        return from != null ? from : Instant.EPOCH;
    }

    private static Instant effectiveTo(Instant to) {
        return to != null ? to : Instant.now();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private record TimeSlot(DayOfWeek dayOfWeek, int hour) {
    }

    private static final class Bucket {
        private long postCount;
        private long totalLikes;
        private long totalComments;
        private final Set<String> authors = new HashSet<>();

        void add(String author, long likes, long comments) {
            postCount++;
            totalLikes += likes;
            totalComments += comments;
            if (author != null) {
                authors.add(author);
            }
        }

        long totalEngagement() {
            return totalLikes + totalComments;
        }

        double avgEngagementPerPost() {
            return postCount == 0 ? 0.0 : (double) totalEngagement() / postCount;
        }
    }

    private static final class CohortAccumulator {
        private final String cohort;
        private long totalPosts;
        private long totalLikes;
        private long totalComments;
        private final Set<String> authors = new HashSet<>();
        private double sentimentScoreSum;
        private long sentimentScoreCount;
        private long positiveCount;
        private long sentimentCount;

        CohortAccumulator(String cohort) {
            this.cohort = cohort;
        }

        void add(String author, long likes, long comments, Short sentimentScore, Sentiment sentiment) {
            totalPosts++;
            totalLikes += likes;
            totalComments += comments;
            if (author != null) {
                authors.add(author);
            }
            if (sentimentScore != null) {
                sentimentScoreSum += sentimentScore;
                sentimentScoreCount++;
            }
            if (sentiment != null) {
                sentimentCount++;
                if (sentiment == Sentiment.POSITIVE) {
                    positiveCount++;
                }
            }
        }

        CohortEngagementStats toStats(int movieCount) {
            long totalEngagement = totalLikes + totalComments;
            double avgEngagementPerPost = totalPosts == 0 ? 0.0 : (double) totalEngagement / totalPosts;
            double avgSentimentScore = sentimentScoreCount == 0 ? 0.0 : sentimentScoreSum / sentimentScoreCount;
            double positiveRatio = sentimentCount == 0 ? 0.0 : (double) positiveCount / sentimentCount;
            return new CohortEngagementStats(cohort, movieCount, totalPosts, authors.size(),
                    totalLikes, totalComments, totalEngagement, avgEngagementPerPost,
                    avgSentimentScore, positiveRatio);
        }
    }
}
