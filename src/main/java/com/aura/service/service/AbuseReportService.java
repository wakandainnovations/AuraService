package com.aura.service.service;

import com.aura.service.abuse.AbuseReportDispatcher;
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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AbuseReportService {

    private final AbuseReportRepository abuseReportRepository;
    private final MentionRepository mentionRepository;
    private final UserRepository userRepository;
    private final AbuseReportDispatcher abuseReportDispatcher;
    private final Clock clock;

    @Transactional
    public Optional<AbuseReport> report(Long mentionId, ReportAbuseRequest request, String username) {
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

        return Optional.of(report);
    }

    /**
     * Reports filed against {@code mentionId}, newest first. Empty {@link Optional} when the mention
     * does not exist, so the caller can return 404 (mirrors {@link #report}).
     */
    @Transactional(readOnly = true)
    public Optional<List<AbuseReport>> listForMention(Long mentionId) {
        if (mentionId == null || !mentionRepository.existsById(mentionId)) {
            return Optional.empty();
        }
        return Optional.of(abuseReportRepository.findByMentionIdOrderBySubmittedAtDesc(mentionId));
    }

    /**
     * The authenticated user's reports, newest first, optionally filtered to a single {@code status}.
     */
    @Transactional(readOnly = true)
    public List<AbuseReport> listForUser(String username, AbuseReport.Status status) {
        Long userId = resolveUserId(username);
        if (status == null) {
            return abuseReportRepository.findByUserIdOrderBySubmittedAtDesc(userId);
        }
        return abuseReportRepository.findByUserIdAndStatusOrderBySubmittedAtDesc(userId, status);
    }

    private Long resolveUserId(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + username));
    }
}
