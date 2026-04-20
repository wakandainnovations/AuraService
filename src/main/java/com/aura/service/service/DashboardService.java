package com.aura.service.service;

import com.aura.service.dto.*;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.aura.service.enums.TimePeriod;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DashboardService {
    
    private final MentionRepository mentionRepository;
    private final ManagedEntityRepository entityRepository;
    
    public EntityStatsResponse getEntityStats(Long entityId) {
        long totalMentions = mentionRepository.countByManagedEntityId(entityId);
        long positiveMentions = mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.POSITIVE);
        long negativeMentions = mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.NEGATIVE);
        long neutralMentions = mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.NEUTRAL);
        Optional<SentimentStats> sentimentStats = mentionRepository.getSentimentStats(entityId);

        double overallSentiment = sentimentStats.map(SentimentStats::getAverageSentimentScore).orElse(0.0);
        double positiveSentiment = totalMentions > 0 ? (double) positiveMentions / totalMentions : 0.0;
        double negativeSentiment = totalMentions > 0 ? (double) negativeMentions / totalMentions : 0.0;
        double neutralSentiment = totalMentions > 0 ? (double) neutralMentions / totalMentions : 0.0;
        double netSentimentScore = negativeMentions > 0 ? (double) positiveMentions / negativeMentions : 0.0;

        return new EntityStatsResponse(totalMentions, positiveSentiment, negativeSentiment, neutralSentiment,
                netSentimentScore, overallSentiment);
    }

    public EntityStatsResponse getClusterStats(List<Long> entityIds) {
        double totalSentiment = 0.0;
        double overallSentiment = 0.0;
        List<Mention> mentions = mentionRepository.findIntersectionOfMentions(entityIds, entityIds.size());

        long totalMentions = mentions.size();
        long positiveMentions = mentions.stream().filter(m -> m.getSentiment() == Sentiment.POSITIVE).count();
        long negativeMentions = mentions.stream().filter(m -> m.getSentiment() == Sentiment.NEGATIVE).count();
        long neutralMentions = mentions.stream().filter(m -> m.getSentiment() == Sentiment.NEUTRAL).count();

        for (long entityId: entityIds){
            Optional<SentimentStats> sentimentStats = mentionRepository.getSentimentStats(entityId);
            double sentiment = sentimentStats.map(SentimentStats::getAverageSentimentScore).orElse(0.0);
            totalSentiment += sentiment;
        }

        overallSentiment = (!entityIds.isEmpty()) ? totalSentiment / entityIds.size() : 0.0;
        double positiveSentiment = totalMentions > 0 ? (double) positiveMentions / totalMentions : 0.0;
        double negativeSentiment = totalMentions > 0 ? (double) negativeMentions / totalMentions : 0.0;
        double neutralSentiment = totalMentions > 0 ? (double) neutralMentions / totalMentions : 0.0;
        double netSentimentScore = negativeMentions > 0 ? (double) positiveMentions / negativeMentions : 0.0;

        return new EntityStatsResponse(totalMentions, positiveSentiment, negativeSentiment, neutralSentiment,
                netSentimentScore, overallSentiment);
    }
    
    public EntityStatsAvgResponse getEntityStatsAvg(Long entityId) {
        long totalMentions = mentionRepository.countByManagedEntityId(entityId);
        long positiveMentions = mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.POSITIVE);
        long negativeMentions = mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.NEGATIVE);
        
        Optional<SentimentStats> sentimentStats = mentionRepository.getSentimentStats(entityId);
        
        double overallSentiment = sentimentStats.map(SentimentStats::getAverageSentimentScore).orElse(0.0);
        double positiveRatio = totalMentions > 0 ? (double) positiveMentions / totalMentions : 0.0;
        double netSentimentScore = negativeMentions > 0 ? (double) positiveMentions / negativeMentions : 0.0;
        
        return new EntityStatsAvgResponse(totalMentions, overallSentiment, positiveRatio, netSentimentScore);
    }
    
    public EntityStatsAvgResponse getEntityStatsAvg(List<Long> entityIds) {
        long totalMentions = mentionRepository.countByManagedEntityIdIn(entityIds);
        long positiveMentions = mentionRepository.countByManagedEntityIdInAndSentiment(entityIds, Sentiment.POSITIVE);
        long negativeMentions = mentionRepository.countByManagedEntityIdInAndSentiment(entityIds, Sentiment.NEGATIVE);
        
        Optional<SentimentStats> sentimentStats = mentionRepository.getSentimentStats(entityIds);
        
        double overallSentiment = sentimentStats.map(SentimentStats::getAverageSentimentScore).orElse(0.0);
        double positiveRatio = totalMentions > 0 ? (double) positiveMentions / totalMentions : 0.0;
        double netSentimentScore = negativeMentions > 0 ? (double) positiveMentions / negativeMentions : 0.0;
        
        return new EntityStatsAvgResponse(totalMentions, overallSentiment, positiveRatio, netSentimentScore);
    }
    
    public List<CompetitorSnapshot> getCompetitorSnapshot(Long entityId) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));
        
        List<CompetitorSnapshot> snapshots = new ArrayList<>();
        
        snapshots.add(createSnapshot(entity));
        
        for (ManagedEntity competitor : entity.getCompetitors()) {
            snapshots.add(createSnapshot(competitor));
        }
        
        return snapshots;
    }
    
    private CompetitorSnapshot createSnapshot(ManagedEntity entity) {
        long entityId = entity.getId();
        long totalMentions = mentionRepository.countByManagedEntityId(entityId);
        long positiveMentions = mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.POSITIVE);
        long negativeMentions = mentionRepository.countByManagedEntityIdAndSentiment(entityId, Sentiment.NEGATIVE);

        Optional<SentimentStats> sentimentStats = mentionRepository.getSentimentStats(entity.getId());

        double overallSentiment = sentimentStats.map(SentimentStats::getAverageSentimentScore).orElse(0.0);
        double positiveRatio = totalMentions > 0 ? (double) positiveMentions / totalMentions : 0.0;
        double netSentimentScore = negativeMentions > 0 ? (double) positiveMentions / negativeMentions : 0.0;

        return new CompetitorSnapshot(entity.getName(), totalMentions, overallSentiment, positiveRatio, netSentimentScore);
    }
    
    public SentimentOverTimeResponse getSentimentOverTime(
            TimePeriod period,
            List<Long> entityIds
    ) {
        List<EntitySentimentData> entitySentiments = new ArrayList<>();
        
        Instant endDate = Instant.now();
        Instant startDate = calculateStartDate(period, endDate);
        
        for (Long currentEntityId : entityIds) {
            ManagedEntity entity = entityRepository.findById(currentEntityId)
                    .orElseThrow(() -> new RuntimeException("Entity not found with id: " + currentEntityId));
            
            List<Mention> mentions = mentionRepository.findByEntityIdsAndDateRange(
                    Collections.singletonList(currentEntityId),
                    startDate,
                    endDate
            );
            
            List<TimeSeriesData> timeSeriesData = aggregateMentionsByPeriod(mentions, period, startDate, endDate);
            entitySentiments.add(new EntitySentimentData(entity.getName(), timeSeriesData));
        }
        
        return new SentimentOverTimeResponse(entitySentiments);
    }
    
    private Instant calculateStartDate(TimePeriod period, Instant endDate) {
        ZonedDateTime zonedDateTime = endDate.atZone(ZoneId.systemDefault());
        return switch (period) {
            case DAY -> endDate.minus(7, ChronoUnit.DAYS);
            case DAY15 -> endDate.minus(15, ChronoUnit.DAYS);
            case DAY30 -> endDate.minus(30, ChronoUnit.DAYS);
            case WEEK -> zonedDateTime.minusWeeks(12).toInstant();
            case MONTH -> zonedDateTime.minusMonths(12).toInstant();
        };
    }
    
    private List<TimeSeriesData> aggregateMentionsByPeriod(
            List<Mention> mentions,
            TimePeriod period,
            Instant startDate,
            Instant endDate
    ) {
        Map<String, TimeSeriesData> dataMap = new LinkedHashMap<>();
        
        Instant current = startDate;
        DateTimeFormatter formatter = getFormatterForPeriod(period);
        
        while (current.isBefore(endDate) || current.equals(endDate)) {
            String dateKey = formatDate(current, formatter);
            dataMap.put(dateKey, new TimeSeriesData(dateKey, 0, 0, 0));
            current = incrementByPeriod(current, period);
        }
        
        for (Mention mention : mentions) {
            String dateKey = formatDate(mention.getPostDate(), formatter);
            TimeSeriesData data = dataMap.get(dateKey);
            if (data != null) {
                switch (mention.getSentiment()) {
                    case POSITIVE -> data.setPositive(data.getPositive() + 1);
                    case NEGATIVE -> data.setNegative(data.getNegative() + 1);
                    case NEUTRAL -> data.setNeutral(data.getNeutral() + 1);
                }
            }
        }
        
        return new ArrayList<>(dataMap.values());
    }
    
    private DateTimeFormatter getFormatterForPeriod(TimePeriod period) {
        return switch (period) {
            case DAY -> DateTimeFormatter.ofPattern("yyyy-MM-dd");
            case DAY15 -> DateTimeFormatter.ofPattern("yyyy-MM-dd");
            case DAY30 -> DateTimeFormatter.ofPattern("yyyy-MM-dd");
            case WEEK -> DateTimeFormatter.ofPattern("yyyy-'W'ww");
            case MONTH -> DateTimeFormatter.ofPattern("yyyy-MM");
        };
    }
    
    private String formatDate(Instant instant, DateTimeFormatter formatter) {
        return LocalDate.ofInstant(instant, ZoneId.systemDefault()).format(formatter);
    }
    
    private Instant incrementByPeriod(Instant instant, TimePeriod period) {
        ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());
        return switch (period) {
            case DAY -> instant.plus(1, ChronoUnit.DAYS);
            case DAY15 -> instant.plus(1, ChronoUnit.DAYS);
            case DAY30 -> instant.plus(1, ChronoUnit.DAYS);
            case WEEK -> zonedDateTime.plusWeeks(1).toInstant();
            case MONTH -> zonedDateTime.plusMonths(1).toInstant();
        };
    }
    
    public Map<String, Map<String, Long>> getPlatformMentions(Long entityId) {
        List<Object[]> results = mentionRepository.countByPlatformForEntity(entityId);
        
        Map<String, Map<String, Long>> platformCounts = new HashMap<>();
        for (Object[] result : results) {
            Platform platform = (Platform) result[0];
            Sentiment sentiment = (Sentiment) result[1];
            Long count = (Long) result[2];
            
            platformCounts.computeIfAbsent(platform.name(), k -> new HashMap<>()).put(sentiment.name(), count);
        }
        
        return platformCounts;
    }

    public Map<String, Map<String, Long>> getPlatformMentionsForCluster(List<Long> entityIds) {
        List<Mention> mentions = mentionRepository.findIntersectionOfMentions(entityIds, entityIds.size());

        Map<String, Map<String, Long>> platformCounts = new HashMap<>();
        for (Mention mention : mentions) {
            platformCounts.computeIfAbsent(mention.getPlatform().name(), k -> new HashMap<>())
                         .merge(mention.getSentiment().name(), 1L, Long::sum);
        }
        return platformCounts;
    }
    
    public Page<MentionResponse> getMentions(
            Long entityId,
            Platform platform,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("post_date").descending());
        
        List<Long> entityIds = (entityId == null) ? new ArrayList<>() : Collections.singletonList(entityId);
        
        String platformName = (platform == null) ? null : platform.name();
        
        Page<Mention> mentions = mentionRepository.findFilteredMentions(
                entityIds,
                platformName,
                pageable
        );
        
        return mentions.map(this::mapToMentionResponse);
    }
    
    public Page<MentionResponse> getClusterMentions(
            List<Long> entityIds,
            Platform platform,
            int page,
            int size
    ) {
        List<Mention> intersectionMentions = mentionRepository.findIntersectionOfMentions(entityIds, entityIds.size());

        Stream<Mention> mentionsStream = intersectionMentions.stream();

        if (platform != null) {
            mentionsStream = mentionsStream.filter(m -> m.getPlatform() == platform);
        }

        List<Mention> filteredMentions = mentionsStream
                .sorted(Comparator.comparing(Mention::getPostDate).reversed())
                .collect(Collectors.toList());

        Pageable pageable = PageRequest.of(page, size, Sort.by("post_date").descending());

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filteredMentions.size());

        List<Mention> pageContent;
        if (start > filteredMentions.size()) {
            pageContent = Collections.emptyList();
        } else {
            pageContent = filteredMentions.subList(start, end);
        }

        Page<Mention> mentionPage = new PageImpl<>(pageContent, pageable, filteredMentions.size());

        return mentionPage.map(this::mapToMentionResponse);
    }
    
    private MentionResponse mapToMentionResponse(Mention mention) {
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
