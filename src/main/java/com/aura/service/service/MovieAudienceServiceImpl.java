package com.aura.service.service;

import com.aura.service.dto.ComparableMovieStats;
import com.aura.service.dto.LanguageAudienceResponse;
import com.aura.service.dto.MovieAudienceDetailResponse;
import com.aura.service.dto.MovieBudgetComparisonResponse;
import com.aura.service.dto.UserEngagementStats;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MovieAudienceServiceImpl implements MovieAudienceService {

    private static final String MOVIE_TYPE = "MOVIE";

    // Fraction of the target's budget used to build the comparison range: [0.5x, 1.5x].
    private static final double BUDGET_RANGE_FRACTION = 0.5;

    private static final int DEFAULT_USER_LIMIT = 100;
    private static final int MAX_USER_LIMIT = 500;

    private final ManagedEntityRepository entityRepository;
    private final MentionRepository mentionRepository;
    private final EntityAccessService entityAccessService;

    @Override
    public LanguageAudienceResponse getLanguageAudience(String language, Long requestedOwnerId) {
        Long scope = entityAccessService.resolveOwnerScope(requestedOwnerId);
        List<ManagedEntity> movies = scope == null
                ? entityRepository.findByTypeAndLanguageIgnoreCase(MOVIE_TYPE, language)
                : entityRepository.findByTypeAndLanguageIgnoreCaseAndOwnerId(MOVIE_TYPE, language, scope);

        if (movies.isEmpty()) {
            throw new ResourceNotFoundException("No movies found for language: " + language);
        }

        List<Long> entityIds = movies.stream().map(ManagedEntity::getId).toList();
        long uniqueAudience = mentionRepository.countDistinctAuthorsByEntityIdsNonZeroSentiment(entityIds);
        List<String> movieNames = movies.stream().map(ManagedEntity::getName).toList();

        return new LanguageAudienceResponse(language, movies.size(), uniqueAudience, movieNames);
    }

    @Override
    public MovieAudienceDetailResponse getMovieAudienceDetail(
            String language, String movieName, Long requestedOwnerId, Integer limit) {
        Long scope = entityAccessService.resolveOwnerScope(requestedOwnerId);
        List<ManagedEntity> matches = scope == null
                ? entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCase(MOVIE_TYPE, movieName, language)
                : entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
                        MOVIE_TYPE, movieName, language, scope);

        if (matches.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No movie found named '" + movieName + "' in language: " + language);
        }

        List<Long> entityIds = matches.stream().map(ManagedEntity::getId).toList();
        List<Object[]> rows = mentionRepository.findAuthorEngagementStats(entityIds);

        long totalPosts = 0;
        for (Object[] row : rows) {
            totalPosts += (Long) row[1];
        }

        int effectiveLimit = resolveLimit(limit);
        long totalPostsFinal = totalPosts;
        List<UserEngagementStats> users = rows.stream()
                .map(row -> toUserEngagementStats(row, totalPostsFinal))
                .sorted(Comparator.comparingLong(UserEngagementStats::postCount).reversed())
                .limit(effectiveLimit)
                .toList();

        return new MovieAudienceDetailResponse(movieName, language, rows.size(), totalPosts, users);
    }

    @Override
    public MovieBudgetComparisonResponse getBudgetComparison(
            String movieName, String language, Long requestedOwnerId) {
        Long scope = entityAccessService.resolveOwnerScope(requestedOwnerId);
        ManagedEntity target = resolveSingleTargetMovie(movieName, language, scope);

        Double budget = target.getBudget();
        if (budget == null) {
            throw new IllegalArgumentException(
                    "Movie '" + target.getName() + "' has no budget recorded; cannot compare");
        }

        double min = budget * (1 - BUDGET_RANGE_FRACTION);
        double max = budget * (1 + BUDGET_RANGE_FRACTION);

        List<ManagedEntity> comparable = scope == null
                ? entityRepository.findByTypeAndBudgetBetweenAndIdNot(MOVIE_TYPE, min, max, target.getId())
                : entityRepository.findByTypeAndBudgetBetweenAndIdNotAndOwnerId(
                        MOVIE_TYPE, min, max, target.getId(), scope);

        List<Long> allIds = new ArrayList<>(comparable.size() + 1);
        allIds.add(target.getId());
        comparable.forEach(m -> allIds.add(m.getId()));

        Map<Long, long[]> statsByEntityId = mentionRepository.countAudienceAndPostsPerEntity(allIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> new long[]{(Long) row[1], (Long) row[2]}));

        long[] targetStats = statsByEntityId.getOrDefault(target.getId(), new long[]{0L, 0L});
        long targetAudience = targetStats[0];
        long targetPosts = targetStats[1];

        long maxAudienceInRange = Math.max(targetAudience, comparable.stream()
                .mapToLong(movie -> statsByEntityId.getOrDefault(movie.getId(), new long[]{0L, 0L})[0])
                .max()
                .orElse(0L));

        List<ComparableMovieStats> comparableStats = comparable.stream()
                .map(movie -> toComparableMovieStats(movie, statsByEntityId, maxAudienceInRange))
                .sorted(Comparator.comparingLong(ComparableMovieStats::uniqueAudienceCount).reversed())
                .toList();

        Double targetAudiencePercentileInRange = maxAudienceInRange == 0
                ? null
                : (double) targetAudience / maxAudienceInRange * 100;

        return new MovieBudgetComparisonResponse(
                target.getName(), target.getLanguage(), budget, targetAudience, targetPosts,
                targetAudiencePercentileInRange, min, max, comparableStats);
    }

    private ManagedEntity resolveSingleTargetMovie(String movieName, String language, Long scope) {
        boolean hasLanguage = StringUtils.hasText(language);
        List<ManagedEntity> matches;
        if (hasLanguage) {
            matches = scope == null
                    ? entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCase(MOVIE_TYPE, movieName, language)
                    : entityRepository.findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
                            MOVIE_TYPE, movieName, language, scope);
        } else {
            matches = scope == null
                    ? entityRepository.findByTypeAndNameIgnoreCase(MOVIE_TYPE, movieName)
                    : entityRepository.findByTypeAndNameIgnoreCaseAndOwnerId(MOVIE_TYPE, movieName, scope);
        }

        if (matches.isEmpty()) {
            throw new ResourceNotFoundException("No movie found named: " + movieName);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Multiple movies named '" + movieName + "' found" +
                            (hasLanguage ? "" : "; pass language to disambiguate"));
        }
        return matches.get(0);
    }

    private UserEngagementStats toUserEngagementStats(Object[] row, long totalPosts) {
        String author = (String) row[0];
        long postCount = (Long) row[1];
        double averageSentimentScore = row[2] == null ? 0.0 : ((Number) row[2]).doubleValue();
        long positiveCount = row[3] == null ? 0L : (Long) row[3];

        double engagementRatio = totalPosts == 0 ? 0.0 : (double) postCount / totalPosts;
        double positiveRatio = postCount == 0 ? 0.0 : (double) positiveCount / postCount;

        return new UserEngagementStats(author, postCount, engagementRatio, averageSentimentScore, positiveRatio);
    }

    private ComparableMovieStats toComparableMovieStats(
            ManagedEntity movie, Map<Long, long[]> statsByEntityId, long maxAudienceInRange) {
        long[] stats = statsByEntityId.getOrDefault(movie.getId(), new long[]{0L, 0L});
        long uniqueAudienceCount = stats[0];
        long totalPosts = stats[1];
        Double audiencePercentileInRange = maxAudienceInRange == 0
                ? null
                : (double) uniqueAudienceCount / maxAudienceInRange * 100;

        return new ComparableMovieStats(
                movie.getName(), movie.getLanguage(), movie.getBudget(),
                uniqueAudienceCount, totalPosts, audiencePercentileInRange);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_USER_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_USER_LIMIT));
    }
}
