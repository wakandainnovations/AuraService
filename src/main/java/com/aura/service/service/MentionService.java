package com.aura.service.service;

import com.aura.service.entity.Mention;
import com.aura.service.repository.AbuseReportRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.MobilizeActionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MentionService {

    private final MentionRepository mentionRepository;
    private final AbuseReportRepository abuseReportRepository;
    private final ReplyDraftRepository replyDraftRepository;
    private final MobilizeActionRepository mobilizeActionRepository;
    private final CrisisPlanRepository crisisPlanRepository;
    private final EntityAccessService entityAccessService;

    /**
     * Permanently removes a mention and everything that hangs off it. Intended for cleaning up
     * false-positive mentions that slipped past sentiment scoring.
     *
     * <p>The dependent tables reference a mention by a plain {@code mention_id} column (no database
     * foreign key), so they are deleted explicitly here to avoid leaving orphaned rows behind.
     *
     * @return {@code true} if a mention with the given id existed and was deleted, {@code false} otherwise.
     */
    @Transactional
    public boolean deleteMention(Long mentionId) {
        Mention mention = mentionRepository.findById(mentionId).orElse(null);
        if (mention == null) {
            return false;
        }
        // A mention may only be purged by the owner of an entity it is attributed to.
        entityAccessService.assertMentionAccessible(mention);

        abuseReportRepository.deleteByMentionId(mentionId);
        replyDraftRepository.deleteByMentionId(mentionId);
        mobilizeActionRepository.deleteByMentionId(mentionId);
        crisisPlanRepository.deleteByMentionId(mentionId);

        mentionRepository.delete(mention);
        return true;
    }
}
