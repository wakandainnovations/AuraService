package com.aura.service.service;

import com.aura.service.dto.WorkspaceImpactResponse;
import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.CrisisPlan;
import com.aura.service.entity.MobilizeAction;
import com.aura.service.entity.ReplyDraft;
import com.aura.service.entity.ReplyTemplate;
import com.aura.service.repository.AbuseReportRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.MobilizeActionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.repository.ReplyTemplateRepository;
import com.aura.service.repository.UserEntityViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates everything the user has accumulated in their workspace into a single
 * {@link WorkspaceImpactResponse} — the "investment made visible".
 * <p>
 * Stored value (templates, playbooks) and effort already spent (posted replies, rallied allies,
 * upheld reports) compound loyalty only when the user can <em>see</em> them compounding. This
 * service produces both the raw counters and ready-to-display highlight sentences for that purpose.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceImpactService {

    private final ReplyTemplateRepository templateRepository;
    private final CrisisPlanRepository crisisPlanRepository;
    private final ReplyDraftRepository replyDraftRepository;
    private final MobilizeActionRepository mobilizeActionRepository;
    private final AbuseReportRepository abuseReportRepository;
    private final UserEntityViewRepository userEntityViewRepository;

    @Transactional(readOnly = true)
    public WorkspaceImpactResponse getImpact(Long userId) {
        List<ReplyTemplate> templates = templateRepository.findByUserIdOrderByCreatedAtDesc(userId);
        long templateCount = templates.size();
        long draftsSavedByTemplates = templates.stream()
                .mapToLong(ReplyTemplate::getUseCount)
                .sum();

        List<CrisisPlan> playbooks = crisisPlanRepository.findByCreatedBy(userId);
        long playbookCount = playbooks.size();
        long favoritePlaybookCount = playbooks.stream()
                .filter(CrisisPlan::isFavorite)
                .count();

        long repliesPosted = replyDraftRepository.countByUserIdAndStatus(userId, ReplyDraft.Status.POSTED);

        long alliesMobilized = mobilizeActionRepository.findByUserId(userId).stream()
                .mapToLong(MobilizeAction::getAllyCount)
                .sum();

        long abuseReportsFiled = abuseReportRepository.countByUserId(userId);
        long abuseReportsUpheld =
                abuseReportRepository.countByUserIdAndStatus(userId, AbuseReport.Status.UPHELD);

        long entitiesWatched = userEntityViewRepository.countByUserId(userId);

        WorkspaceImpactResponse response = WorkspaceImpactResponse.builder()
                .entitiesWatched(entitiesWatched)
                .templateCount(templateCount)
                .draftsSavedByTemplates(draftsSavedByTemplates)
                .playbookCount(playbookCount)
                .favoritePlaybookCount(favoritePlaybookCount)
                .repliesPosted(repliesPosted)
                .alliesMobilized(alliesMobilized)
                .abuseReportsFiled(abuseReportsFiled)
                .abuseReportsUpheld(abuseReportsUpheld)
                .build();

        response.setHighlights(buildHighlights(response));
        return response;
    }

    /**
     * Turns the non-zero metrics into short, display-ready sentences, ordered most-rewarding first:
     * mastery (playbooks) and justice (upheld reports) lead, time saved and reach follow, with the
     * lightweight "what you're watching" line last.
     */
    private List<String> buildHighlights(WorkspaceImpactResponse r) {
        List<String> highlights = new ArrayList<>();

        if (r.getPlaybookCount() > 0) {
            highlights.add(String.format("Your playbook library has handled %d %s.",
                    r.getPlaybookCount(), noun(r.getPlaybookCount(), "crisis", "crises")));
        }
        if (r.getAbuseReportsUpheld() > 0) {
            long n = r.getAbuseReportsUpheld();
            highlights.add(String.format("%d of your abuse reports %s upheld — %d %s removed.",
                    n, n == 1 ? "was" : "were", n, noun(n, "post", "posts")));
        }
        if (r.getDraftsSavedByTemplates() > 0) {
            highlights.add(String.format("Your templates have saved you %d %s.",
                    r.getDraftsSavedByTemplates(),
                    noun(r.getDraftsSavedByTemplates(), "draft", "drafts")));
        }
        if (r.getRepliesPosted() > 0) {
            highlights.add(String.format("You've posted %d on-brand %s.",
                    r.getRepliesPosted(), noun(r.getRepliesPosted(), "reply", "replies")));
        }
        if (r.getAlliesMobilized() > 0) {
            highlights.add(String.format("You've rallied %d %s to amplify the good moments.",
                    r.getAlliesMobilized(), noun(r.getAlliesMobilized(), "ally", "allies")));
        }
        if (r.getEntitiesWatched() > 0) {
            highlights.add(String.format("You're protecting the reputation of %d %s.",
                    r.getEntitiesWatched(), noun(r.getEntitiesWatched(), "entity", "entities")));
        }

        return highlights;
    }

    private static String noun(long count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }
}
