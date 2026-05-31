package com.aura.service.service;

import com.aura.service.dto.ReportAbuseRequest;
import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.User;
import com.aura.service.repository.AbuseReportRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AbuseReportService {

    private final AbuseReportRepository abuseReportRepository;
    private final MentionRepository mentionRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public Optional<AbuseReport> report(Long mentionId, ReportAbuseRequest request, String username) {
        if (!mentionRepository.existsById(mentionId)) {
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

        return Optional.of(abuseReportRepository.save(report));
    }

    private Long resolveUserId(String username) {
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + username));
    }
}
