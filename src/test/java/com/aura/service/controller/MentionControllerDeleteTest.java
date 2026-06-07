package com.aura.service.controller;

import com.aura.service.entity.Mention;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.repository.AbuseReportRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.MobilizeActionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.service.MentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MentionControllerDeleteTest {

    private MentionRepository mentionRepository;
    private AbuseReportRepository abuseReportRepository;
    private ReplyDraftRepository replyDraftRepository;
    private MobilizeActionRepository mobilizeActionRepository;
    private CrisisPlanRepository crisisPlanRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        abuseReportRepository = mock(AbuseReportRepository.class);
        replyDraftRepository = mock(ReplyDraftRepository.class);
        mobilizeActionRepository = mock(MobilizeActionRepository.class);
        crisisPlanRepository = mock(CrisisPlanRepository.class);

        MentionService service = new MentionService(
                mentionRepository,
                abuseReportRepository,
                replyDraftRepository,
                mobilizeActionRepository,
                crisisPlanRepository);
        MentionController controller = new MentionController(service);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Mention mention(Long id) {
        Mention m = new Mention();
        m.setId(id);
        m.setPostId("x-12345");
        return m;
    }

    @Test
    void delete_returns204AndCleansUpDependents() throws Exception {
        Mention m = mention(1L);
        when(mentionRepository.findById(1L)).thenReturn(Optional.of(m));

        mvc.perform(delete("/api/mentions/{mentionId}", 1L))
                .andExpect(status().isNoContent());

        verify(abuseReportRepository).deleteByMentionId(1L);
        verify(replyDraftRepository).deleteByMentionId(1L);
        verify(mobilizeActionRepository).deleteByMentionId(1L);
        verify(crisisPlanRepository).deleteByMentionId(1L);
        verify(mentionRepository).delete(m);
    }

    @Test
    void delete_returns404WhenMentionNotFound() throws Exception {
        when(mentionRepository.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(delete("/api/mentions/{mentionId}", 999L))
                .andExpect(status().isNotFound());

        verify(mentionRepository, never()).delete(any(Mention.class));
        verify(abuseReportRepository, never()).deleteByMentionId(anyLong());
        verify(replyDraftRepository, never()).deleteByMentionId(anyLong());
        verify(mobilizeActionRepository, never()).deleteByMentionId(anyLong());
        verify(crisisPlanRepository, never()).deleteByMentionId(anyLong());
    }
}
