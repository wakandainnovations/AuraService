package com.aura.service.service;

import com.aura.service.abuse.AbuseReportDispatcher;
import com.aura.service.dto.AbuseReportDto;
import com.aura.service.dto.MentionSummaryDto;
import com.aura.service.dto.ReportAbuseRequest;
import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.Mention;
import com.aura.service.entity.User;
import com.aura.service.repository.AbuseReportRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AbuseReportService {

    private final AbuseReportRepository abuseReportRepository;
    private final MentionRepository mentionRepository;
    private final UserRepository userRepository;
    private final AbuseReportDispatcher abuseReportDispatcher;
    private final Clock clock;

    @Transactional
    public Optional<AbuseReportDto> report(Long mentionId, ReportAbuseRequest request, String username) {
        Optional<Mention> mention = mentionRepository.findById(mentionId);
        if (mention.isEmpty()) {
            return Optional.empty();
        }

        Long userId = resolveUserId(username);

        AbuseReport report = AbuseReport.builder()
                .mentionId(mentionId)
                .userId(userId)
                .category(request.getCategory())
                .notes(request.getNotes())
                .status(AbuseReport.Status.SUBMITTED)
                .submittedAt(clock.instant())
                .build();

        report = abuseReportRepository.save(report);

        // Forward to the platform-specific moderation backend; the returned ticket reference
        // is persisted via dirty checking on the managed entity within this transaction.
        String externalRef = abuseReportDispatcher.dispatch(report, mention.get());
        if (externalRef != null) {
            report.setExternalRef(externalRef);
        }

        return Optional.of(AbuseReportDto.of(report, MentionSummaryDto.from(mention.get())));
    }

    /**
     * Reports filed against {@code mentionId}, newest first. Empty {@link Optional} when the mention
     * does not exist, so the caller can return 404 (mirrors {@link #report}). Every report carries the
     * same nested mention summary, loaded once.
     */
    @Transactional(readOnly = true)
    public Optional<List<AbuseReportDto>> listForMention(Long mentionId) {
        if (mentionId == null) {
            return Optional.empty();
        }
        Optional<Mention> mention = mentionRepository.findById(mentionId);
        if (mention.isEmpty()) {
            return Optional.empty();
        }
        MentionSummaryDto summary = MentionSummaryDto.from(mention.get());
        List<AbuseReportDto> reports = abuseReportRepository.findByMentionIdOrderBySubmittedAtDesc(mentionId)
                .stream()
                .map(report -> AbuseReportDto.of(report, summary))
                .toList();
        return Optional.of(reports);
    }

    /**
     * The authenticated user's reports, newest first, optionally filtered to a single {@code status}.
     */
    @Transactional(readOnly = true)
    public List<AbuseReportDto> listForUser(String username, AbuseReport.Status status) {
        Long userId = resolveUserId(username);
        List<AbuseReport> reports = (status == null)
                ? abuseReportRepository.findByUserIdOrderBySubmittedAtDesc(userId)
                : abuseReportRepository.findByUserIdAndStatusOrderBySubmittedAtDesc(userId, status);
        return enrichWithMentions(reports);
    }

    /**
     * Attaches the nested mention summary to each report, batch-loading all referenced mentions in a
     * single {@code findAllById} to avoid an N+1 query. Reports whose mention has been deleted get a
     * {@code null} mention.
     */
    private List<AbuseReportDto> enrichWithMentions(List<AbuseReport> reports) {
        if (reports.isEmpty()) {
            return List.of();
        }
        List<Long> mentionIds = reports.stream()
                .map(AbuseReport::getMentionId)
                .distinct()
                .toList();
        Map<Long, MentionSummaryDto> summaries = mentionRepository.findAllById(mentionIds).stream()
                .collect(Collectors.toMap(Mention::getId, MentionSummaryDto::from, (a, b) -> a));
        return reports.stream()
                .map(report -> AbuseReportDto.of(report, summaries.get(report.getMentionId())))
                .toList();
    }

    private Long resolveUserId(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + username));
    }
}
