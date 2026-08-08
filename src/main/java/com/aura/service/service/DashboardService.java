package com.aura.service.service;

import com.aura.service.dto.*;
import com.aura.service.entity.Checkpoint;
import com.aura.service.entity.CrisisPlan;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.ReplyDraft;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.aura.service.enums.TimePeriod;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DashboardService {
    
    private static final List<String> AVAILABLE_ACTIONS =
            List.of("draft-reply", "escalate", "mobilize", "report-abuse");

    private final MentionRepository mentionRepository;
    private final ManagedEntityRepository entityRepository;
    private final ReplyDraftRepository replyDraftRepository;
    private final CrisisPlanRepository crisisPlanRepository;
    private final CheckpointRepository checkpointRepository;
    private final ImpressionsResolver impressionsResolver;
    
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
//        Instant endDate = ZonedDateTime.of(2026, 3, 16, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
        Instant startDate = calculateStartDate(period, endDate);

        LocalDate rangeStart = LocalDate.ofInstant(startDate, ZoneId.systemDefault());
        LocalDate rangeEnd = LocalDate.ofInstant(endDate, ZoneId.systemDefault());
        DateTimeFormatter formatter = getFormatterForPeriod(period);

        for (Long currentEntityId : entityIds) {
            ManagedEntity entity = entityRepository.findById(currentEntityId)
                    .orElseThrow(() -> new RuntimeException("Entity not found with id: " + currentEntityId));

            List<Mention> mentions = mentionRepository.findByEntityIdsAndDateRange(
                    Collections.singletonList(currentEntityId),
                    startDate,
                    endDate
            );

            List<TimeSeriesData> timeSeriesData = aggregateMentionsByPeriod(mentions, period, startDate, endDate);

            List<CheckpointMarker> markers = checkpointRepository
                    .findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                            currentEntityId, rangeStart, rangeEnd)
                    .stream()
                    .filter(cp -> !cp.getCheckpointDate().isBefore(rangeStart)
                            && !cp.getCheckpointDate().isAfter(rangeEnd))
                    .map(cp -> new CheckpointMarker(
                            cp.getCheckpointDate().format(formatter),
                            cp.getDescription()))
                    .toList();

            entitySentiments.add(new EntitySentimentData(entity.getName(), timeSeriesData, markers));
        }

        return new SentimentOverTimeResponse(entitySentiments);
    }
    
    private Instant calculateStartDate(TimePeriod period, Instant endDate) {
        ZonedDateTime zonedDateTime = endDate.atZone(ZoneId.systemDefault());
        return switch (period) {
            case DAY -> endDate.minus(7, ChronoUnit.DAYS);
            case DAY15 -> endDate.minus(15, ChronoUnit.DAYS);
            case DAY30 -> endDate.minus(30, ChronoUnit.DAYS);
            case DAY90 -> endDate.minus(90, ChronoUnit.DAYS);
            case WEEK -> zonedDateTime.minusWeeks(12).toInstant();
            case MONTH -> zonedDateTime.minusMonths(12).toInstant();
            case MONTH6 -> zonedDateTime.minusMonths(6).toInstant();
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
            dataMap.put(dateKey, new TimeSeriesData(dateKey, 0, 0, 0, 0));
            current = incrementByPeriod(current, period);
        }
        
        for (Mention mention : mentions) {
            String dateKey = formatDate(mention.getPostDate(), formatter);
            TimeSeriesData data = dataMap.get(dateKey);
            if (data != null) {
                data.setTotal(data.getTotal() + 1);

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
            case DAY90 -> DateTimeFormatter.ofPattern("yyyy-MM-dd");
            case WEEK -> DateTimeFormatter.ofPattern("yyyy-'W'ww");
            case MONTH -> DateTimeFormatter.ofPattern("yyyy-MM");
            case MONTH6 -> DateTimeFormatter.ofPattern("yyyy-MM");
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
            case DAY90 -> instant.plus(1, ChronoUnit.DAYS);
            case WEEK -> zonedDateTime.plusWeeks(1).toInstant();
            case MONTH -> zonedDateTime.plusMonths(1).toInstant();
            case MONTH6 -> zonedDateTime.plusMonths(1).toInstant();
        };
    }
    
    public SentimentOverTimeResponse getSentimentOverTimeForRange(
            LocalDate startDate,
            LocalDate endDate,
            List<Long> entityIds
    ) {
        List<EntitySentimentData> entitySentiments = new ArrayList<>();

        Instant rangeStartInstant = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant rangeEndInstant = endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusNanos(1);

        Granularity granularity = resolveGranularity(startDate, endDate);
        DateTimeFormatter formatter = getFormatterForGranularity(granularity);

        for (Long currentEntityId : entityIds) {
            ManagedEntity entity = entityRepository.findById(currentEntityId)
                    .orElseThrow(() -> new RuntimeException("Entity not found with id: " + currentEntityId));

            List<Mention> mentions = mentionRepository.findByEntityIdsAndDateRange(
                    Collections.singletonList(currentEntityId),
                    rangeStartInstant,
                    rangeEndInstant
            );

            List<TimeSeriesData> timeSeriesData = aggregateMentionsByGranularity(
                    mentions, granularity, rangeStartInstant, rangeEndInstant);

            List<CheckpointMarker> markers = checkpointRepository
                    .findByManagedEntityIdAndCheckpointDateBetweenOrderByCheckpointDateAsc(
                            currentEntityId, startDate, endDate)
                    .stream()
                    .map(cp -> new CheckpointMarker(
                            cp.getCheckpointDate().format(formatter),
                            cp.getDescription()))
                    .toList();

            entitySentiments.add(new EntitySentimentData(entity.getName(), timeSeriesData, markers));
        }

        return new SentimentOverTimeResponse(entitySentiments);
    }

    private enum Granularity { DAILY, WEEKLY, MONTHLY }

    private Granularity resolveGranularity(LocalDate startDate, LocalDate endDate) {
        long days = ChronoUnit.DAYS.between(startDate, endDate);
        if (days <= 90) {
            return Granularity.DAILY;
        } else if (days <= 365) {
            return Granularity.WEEKLY;
        }
        return Granularity.MONTHLY;
    }

    private DateTimeFormatter getFormatterForGranularity(Granularity granularity) {
        return switch (granularity) {
            case DAILY -> DateTimeFormatter.ofPattern("yyyy-MM-dd");
            case WEEKLY -> DateTimeFormatter.ofPattern("yyyy-'W'ww");
            case MONTHLY -> DateTimeFormatter.ofPattern("yyyy-MM");
        };
    }

    private List<TimeSeriesData> aggregateMentionsByGranularity(
            List<Mention> mentions,
            Granularity granularity,
            Instant startDate,
            Instant endDate
    ) {
        Map<String, TimeSeriesData> dataMap = new LinkedHashMap<>();

        Instant current = startDate;
        DateTimeFormatter formatter = getFormatterForGranularity(granularity);

        while (current.isBefore(endDate) || current.equals(endDate)) {
            String dateKey = formatDate(current, formatter);
            dataMap.put(dateKey, new TimeSeriesData(dateKey, 0, 0, 0, 0));
            current = incrementByGranularity(current, granularity);
        }

        for (Mention mention : mentions) {
            String dateKey = formatDate(mention.getPostDate(), formatter);
            TimeSeriesData data = dataMap.get(dateKey);
            if (data != null) {
                data.setTotal(data.getTotal() + 1);

                switch (mention.getSentiment()) {
                    case POSITIVE -> data.setPositive(data.getPositive() + 1);
                    case NEGATIVE -> data.setNegative(data.getNegative() + 1);
                    case NEUTRAL -> data.setNeutral(data.getNeutral() + 1);
                }
            }
        }

        return new ArrayList<>(dataMap.values());
    }

    private Instant incrementByGranularity(Instant instant, Granularity granularity) {
        ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());
        return switch (granularity) {
            case DAILY -> instant.plus(1, ChronoUnit.DAYS);
            case WEEKLY -> zonedDateTime.plusWeeks(1).toInstant();
            case MONTHLY -> zonedDateTime.plusMonths(1).toInstant();
        };
    }

    public SentimentDeltaResponse getSentimentDelta(
            Long entityId, LocalDate fromDate, LocalDate toDate, int windowDays
    ) {
        Instant fromStart = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant fromEnd = fromDate.plusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        Instant toStart = toDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toEnd = toDate.plusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

        long fromPositive = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entityId, Sentiment.POSITIVE, fromStart, fromEnd);
        long fromNegative = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entityId, Sentiment.NEGATIVE, fromStart, fromEnd);
        long fromNeutral = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entityId, Sentiment.NEUTRAL, fromStart, fromEnd);
        long fromTotal = fromPositive + fromNegative + fromNeutral;

        long toPositive = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entityId, Sentiment.POSITIVE, toStart, toEnd);
        long toNegative = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entityId, Sentiment.NEGATIVE, toStart, toEnd);
        long toNeutral = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                entityId, Sentiment.NEUTRAL, toStart, toEnd);
        long toTotal = toPositive + toNegative + toNeutral;

        double fromPositiveRatio = fromTotal > 0 ? (double) fromPositive / fromTotal : 0.0;
        double toPositiveRatio = toTotal > 0 ? (double) toPositive / toTotal : 0.0;
        double fromNetSentiment = fromNegative > 0 ? (double) fromPositive / fromNegative : 0.0;
        double toNetSentiment = toNegative > 0 ? (double) toPositive / toNegative : 0.0;

        String fromLabel = checkpointRepository.findByManagedEntityIdAndCheckpointDate(entityId, fromDate)
                .map(cp -> cp.getDescription()).orElse(null);
        String toLabel = checkpointRepository.findByManagedEntityIdAndCheckpointDate(entityId, toDate)
                .map(cp -> cp.getDescription()).orElse(null);

        return SentimentDeltaResponse.builder()
                .fromDate(fromDate)
                .toDate(toDate)
                .fromLabel(fromLabel)
                .toLabel(toLabel)
                .fromTotalMentions(fromTotal)
                .toTotalMentions(toTotal)
                .mentionsDelta(toTotal - fromTotal)
                .fromPositiveRatio(fromPositiveRatio)
                .toPositiveRatio(toPositiveRatio)
                .positiveRatioDelta(toPositiveRatio - fromPositiveRatio)
                .fromNetSentiment(fromNetSentiment)
                .toNetSentiment(toNetSentiment)
                .netSentimentDelta(toNetSentiment - fromNetSentiment)
                .build();
    }

    public CheckpointImpactResponse getCheckpointImpact(Long entityId, int windowDays) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        List<Checkpoint> checkpoints = checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(entityId);

        List<CheckpointImpact> impacts = new ArrayList<>();
        for (Checkpoint cp : checkpoints) {
            LocalDate cpDate = cp.getCheckpointDate();
            Instant beforeStart = cpDate.minusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant beforeEnd = cpDate.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
            Instant afterStart = cpDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant afterEnd = cpDate.plusDays(windowDays).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

            long beforePositive = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                    entityId, Sentiment.POSITIVE, beforeStart, beforeEnd);
            long beforeNegative = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                    entityId, Sentiment.NEGATIVE, beforeStart, beforeEnd);
            long beforeNeutral = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                    entityId, Sentiment.NEUTRAL, beforeStart, beforeEnd);
            long beforeTotal = beforePositive + beforeNegative + beforeNeutral;

            long afterPositive = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                    entityId, Sentiment.POSITIVE, afterStart, afterEnd);
            long afterNegative = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                    entityId, Sentiment.NEGATIVE, afterStart, afterEnd);
            long afterNeutral = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                    entityId, Sentiment.NEUTRAL, afterStart, afterEnd);
            long afterTotal = afterPositive + afterNegative + afterNeutral;

            double beforePositiveRatio = beforeTotal > 0 ? (double) beforePositive / beforeTotal : 0.0;
            double afterPositiveRatio = afterTotal > 0 ? (double) afterPositive / afterTotal : 0.0;
            double beforeNetSentiment = beforeNegative > 0 ? (double) beforePositive / beforeNegative : 0.0;
            double afterNetSentiment = afterNegative > 0 ? (double) afterPositive / afterNegative : 0.0;
            double netSentimentChange = afterNetSentiment - beforeNetSentiment;

            String impactDirection;
            if (netSentimentChange > 0.05) {
                impactDirection = "POSITIVE";
            } else if (netSentimentChange < -0.05) {
                impactDirection = "NEGATIVE";
            } else {
                impactDirection = "NEUTRAL";
            }

            impacts.add(CheckpointImpact.builder()
                    .checkpointId(cp.getId())
                    .checkpointDate(cpDate)
                    .description(cp.getDescription())
                    .beforeTotalMentions(beforeTotal)
                    .afterTotalMentions(afterTotal)
                    .beforePositiveRatio(beforePositiveRatio)
                    .afterPositiveRatio(afterPositiveRatio)
                    .positiveRatioChange(afterPositiveRatio - beforePositiveRatio)
                    .beforeNetSentiment(beforeNetSentiment)
                    .afterNetSentiment(afterNetSentiment)
                    .netSentimentChange(netSentimentChange)
                    .impactDirection(impactDirection)
                    .build());
        }

        return CheckpointImpactResponse.builder()
                .entityId(entityId)
                .entityName(entity.getName())
                .windowDays(windowDays)
                .impacts(impacts)
                .build();
    }

    public CheckpointTrendResponse getCheckpointTrend(Long entityId) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        List<Checkpoint> checkpoints = checkpointRepository.findByManagedEntityIdOrderByCheckpointDateAsc(entityId);

        List<CheckpointTrendPoint> trendPoints = new ArrayList<>();
        Double previousPositiveRatio = null;
        Double previousNetSentiment = null;

        for (int i = 0; i < checkpoints.size(); i++) {
            Checkpoint cp = checkpoints.get(i);
            LocalDate cpDate = cp.getCheckpointDate();

            LocalDate periodStart;
            if (i == 0) {
                periodStart = entity.getReleaseDate() != null
                        ? entity.getReleaseDate()
                        : cpDate;
            } else {
                periodStart = checkpoints.get(i - 1).getCheckpointDate().plusDays(1);
            }

            Instant periodStartInstant = periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant periodEndInstant = cpDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);

            long periodPositive = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                    entityId, Sentiment.POSITIVE, periodStartInstant, periodEndInstant);
            long periodNegative = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                    entityId, Sentiment.NEGATIVE, periodStartInstant, periodEndInstant);
            long periodNeutral = mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                    entityId, Sentiment.NEUTRAL, periodStartInstant, periodEndInstant);
            long periodMentions = periodPositive + periodNegative + periodNeutral;

            Instant cumulativeCutoff = cpDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
            long cumulativeMentions = mentionRepository.countByManagedEntityIdAndPostDateLessThanEqual(
                    entityId, cumulativeCutoff);

            double positiveRatio = periodMentions > 0 ? (double) periodPositive / periodMentions : 0.0;
            double netSentiment = periodNegative > 0 ? (double) periodPositive / periodNegative : 0.0;

            Double positiveRatioChange = (previousPositiveRatio != null)
                    ? positiveRatio - previousPositiveRatio : null;
            Double netSentimentChange = (previousNetSentiment != null)
                    ? netSentiment - previousNetSentiment : null;

            trendPoints.add(CheckpointTrendPoint.builder()
                    .checkpointDate(cpDate)
                    .description(cp.getDescription())
                    .cumulativeMentions(cumulativeMentions)
                    .periodMentions(periodMentions)
                    .positiveRatio(positiveRatio)
                    .netSentiment(netSentiment)
                    .positiveRatioChangeFromPrevious(positiveRatioChange)
                    .netSentimentChangeFromPrevious(netSentimentChange)
                    .build());

            previousPositiveRatio = positiveRatio;
            previousNetSentiment = netSentiment;
        }

        return CheckpointTrendResponse.builder()
                .entityId(entityId)
                .entityName(entity.getName())
                .trendPoints(trendPoints)
                .build();
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
        List<Mention> mentions = mentionRepository.findUnionOfMentions(entityIds);

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

        Map<Long, ActionHistorySummary> summaries = loadActionSummaries(mentions.getContent());
        Map<Long, String> impressions = impressionsResolver.resolveForMentions(mentions.getContent());
        return mentions.map(m -> mapToMentionResponseWithActions(m, summaries, impressions));
    }
    
    public Page<MentionResponse> getClusterMentions(
            List<Long> entityIds,
            Platform platform,
            int page,
            int size
    ) {
        List<Mention> intersectionMentions = mentionRepository.findUnionOfMentions(entityIds);

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

        Map<Long, ActionHistorySummary> summaries = loadActionSummaries(pageContent);
        Map<Long, String> impressions = impressionsResolver.resolveForMentions(pageContent);
        return mentionPage.map(m -> mapToMentionResponseWithActions(m, summaries, impressions));
    }
    
    public HourlyActivityResponse getHourlyActivity(
            Long entityId,
            TimePeriod period,
            String language,
            String industry,
            String state
    ) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        Instant endDate = Instant.now();
        Instant startDate = calculateStartDate(period, endDate);

        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int h = 0; h < 24; h++) {
            distribution.put(h, 0L);
        }

        List<Object[]> rows = mentionRepository.countActiveUsersByHour(
                entityId, startDate, endDate, language, industry, state
        );
        for (Object[] row : rows) {
            int hour = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            distribution.put(hour, count);
        }

        long totalActiveUsers = mentionRepository.countDistinctActiveUsers(
                entityId, startDate, endDate, language, industry, state
        );

        Map<String, Map<Integer, Long>> dailyDistribution = new LinkedHashMap<>();
        LocalDate startDay = LocalDate.ofInstant(startDate, ZoneOffset.UTC);
        LocalDate endDay = LocalDate.ofInstant(endDate, ZoneOffset.UTC);
        for (LocalDate d = startDay; !d.isAfter(endDay); d = d.plusDays(1)) {
            Map<Integer, Long> hours = new LinkedHashMap<>();
            for (int h = 0; h < 24; h++) {
                hours.put(h, 0L);
            }
            dailyDistribution.put(d.toString(), hours);
        }

        List<Object[]> dailyRows = mentionRepository.countActiveUsersByDayAndHour(
                entityId, startDate, endDate, language, industry, state
        );
        for (Object[] row : dailyRows) {
            String day = (String) row[0];
            int hour = ((Number) row[1]).intValue();
            long count = ((Number) row[2]).longValue();
            Map<Integer, Long> hours = dailyDistribution.get(day);
            if (hours != null) {
                hours.put(hour, count);
            }
        }

        return new HourlyActivityResponse(
                entityId,
                entity.getName(),
                period,
                startDate,
                endDate,
                language,
                industry,
                state,
                totalActiveUsers,
                distribution,
                dailyDistribution
        );
    }

    public AudiencePulseResponse getAudiencePulse(Long entityId) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        List<Object[]> rows = mentionRepository.findRegionBuzzForEntity(entityId);

        long totalMentions = 0;
        for (Object[] row : rows) {
            totalMentions += ((Number) row[1]).longValue();
        }

        List<RegionBuzz> regions = new ArrayList<>();
        int rank = 1;
        for (Object[] row : rows) {
            String region = (String) row[0];
            long mentionCount = ((Number) row[1]).longValue();
            double sharePct = totalMentions > 0 ? (double) mentionCount / totalMentions * 100.0 : 0.0;
            regions.add(new RegionBuzz(rank++, region, mentionCount, sharePct));
        }

        return new AudiencePulseResponse(entityId, entity.getName(), totalMentions, regions);
    }

    public PromotionalMixResponse getPromotionalMix(Long entityId) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        List<Object[]> rows = mentionRepository.findPromotionalMixForEntity(entityId);

        long promotionalCount = 0;
        long organicCount = 0;
        for (Object[] row : rows) {
            long count = ((Number) row[1]).longValue();
            if (Boolean.TRUE.equals(row[0])) {
                promotionalCount += count;
            } else {
                organicCount += count;
            }
        }

        long totalPosts = promotionalCount + organicCount;
        double promotionalSharePct = totalPosts > 0 ? (double) promotionalCount / totalPosts * 100.0 : 0.0;

        return new PromotionalMixResponse(
                entityId, entity.getName(), totalPosts, promotionalCount, organicCount, promotionalSharePct);
    }

    public AuthorTypeBreakdownResponse getAuthorTypeBreakdown(Long entityId) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        List<Object[]> rows = mentionRepository.findAuthorTypeBreakdownForEntity(entityId);

        long totalClassifiedPosts = 0;
        for (Object[] row : rows) {
            totalClassifiedPosts += ((Number) row[1]).longValue();
        }

        List<AuthorTypeCount> authorTypes = new ArrayList<>();
        int rank = 1;
        for (Object[] row : rows) {
            String authorType = (String) row[0];
            long count = ((Number) row[1]).longValue();
            double sharePct = totalClassifiedPosts > 0 ? (double) count / totalClassifiedPosts * 100.0 : 0.0;
            authorTypes.add(new AuthorTypeCount(rank++, authorType, count, sharePct));
        }

        return new AuthorTypeBreakdownResponse(entityId, entity.getName(), totalClassifiedPosts, authorTypes);
    }

    public ContentIntentBreakdownResponse getContentIntentBreakdown(Long entityId) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        List<Object[]> rows = mentionRepository.findContentIntentBreakdownForEntity(entityId);

        long totalClassifiedPosts = 0;
        for (Object[] row : rows) {
            totalClassifiedPosts += ((Number) row[1]).longValue();
        }

        List<ContentIntentCount> intents = new ArrayList<>();
        int rank = 1;
        for (Object[] row : rows) {
            String contentIntent = (String) row[0];
            long count = ((Number) row[1]).longValue();
            double sharePct = totalClassifiedPosts > 0 ? (double) count / totalClassifiedPosts * 100.0 : 0.0;
            intents.add(new ContentIntentCount(rank++, contentIntent, count, sharePct));
        }

        return new ContentIntentBreakdownResponse(entityId, entity.getName(), totalClassifiedPosts, intents);
    }

    public TopicCategoryBreakdownResponse getTopicCategoryBreakdown(Long entityId) {
        ManagedEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + entityId));

        List<Object[]> rows = mentionRepository.findTopicCategoryBreakdownForEntity(entityId);

        long totalClassifiedPosts = 0;
        for (Object[] row : rows) {
            totalClassifiedPosts += ((Number) row[1]).longValue();
        }

        List<TopicCategoryCount> topics = new ArrayList<>();
        int rank = 1;
        for (Object[] row : rows) {
            String topicCategory = (String) row[0];
            long count = ((Number) row[1]).longValue();
            double sharePct = totalClassifiedPosts > 0 ? (double) count / totalClassifiedPosts * 100.0 : 0.0;
            topics.add(new TopicCategoryCount(rank++, topicCategory, count, sharePct));
        }

        return new TopicCategoryBreakdownResponse(entityId, entity.getName(), totalClassifiedPosts, topics);
    }

    private MentionResponse mapToMentionResponseWithActions(
            Mention mention,
            Map<Long, ActionHistorySummary> summaries,
            Map<Long, String> impressions
    ) {
        ActionHistorySummary summary = summaries.getOrDefault(
                mention.getId(), new ActionHistorySummary(0, 0, false));
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
                impressions.getOrDefault(mention.getId(), ImpressionsResolver.NOT_AVAILABLE),
                AVAILABLE_ACTIONS,
                summary
        );
    }

    private Map<Long, ActionHistorySummary> loadActionSummaries(List<Mention> mentions) {
        if (mentions.isEmpty()) {
            return Map.of();
        }
        List<Long> mentionIds = mentions.stream().map(Mention::getId).toList();

        Map<Long, ActionHistorySummary> summaries = new HashMap<>();
        for (Long id : mentionIds) {
            summaries.put(id, new ActionHistorySummary(0, 0, false));
        }

        for (ReplyDraft draft : replyDraftRepository.findByMentionIdIn(mentionIds)) {
            ActionHistorySummary s = summaries.get(draft.getMentionId());
            if (s == null) continue;
            s.setDrafts(s.getDrafts() + 1);
            if (draft.getStatus() == ReplyDraft.Status.POSTED) {
                s.setPosted(s.getPosted() + 1);
            }
        }
        for (CrisisPlan plan : crisisPlanRepository.findByMentionIdIn(mentionIds)) {
            ActionHistorySummary s = summaries.get(plan.getMentionId());
            if (s != null) {
                s.setEscalated(true);
            }
        }
        return summaries;
    }
}
