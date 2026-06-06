package com.aura.service.controller;

import com.aura.service.abuse.AbuseReportDispatcher;
import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.Mention;
import com.aura.service.entity.User;
import com.aura.service.enums.Platform;
import com.aura.service.repository.AbuseReportRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.AbuseReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AbuseReportControllerTest {

    private static final String USERNAME = "ops_user";
    private static final Long USER_ID = 55L;
    private static final Instant NOW = Instant.parse("2026-05-31T12:00:00Z");

    private AbuseReportRepository abuseReportRepository;
    private MentionRepository mentionRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        abuseReportRepository = mock(AbuseReportRepository.class);
        mentionRepository = mock(MentionRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        // Real dispatcher with no strategies — list endpoints never invoke it, and the JDK in use
        // breaks Mockito's inline mock maker for this concrete class (see DashboardControllerWhatsNewTest).
        AbuseReportService service = new AbuseReportService(
                abuseReportRepository,
                mentionRepository,
                userRepository,
                new AbuseReportDispatcher(List.of()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AbuseReportController controller = new AbuseReportController(service);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(USERNAME).password("x").authorities("USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        User userEntity = new User();
        userEntity.setId(USER_ID);
        userEntity.setUsername(USERNAME);
        userEntity.setPassword("x");
        userEntity.setRole("USER");
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(userEntity));
    }

    @Test
    void list_withoutStatus_returnsAllUserReports() throws Exception {
        when(abuseReportRepository.findByUserIdOrderBySubmittedAtDesc(USER_ID))
                .thenReturn(List.of(report(2L, AbuseReport.Status.UPHELD),
                        report(1L, AbuseReport.Status.SUBMITTED)));
        // report(id) references mentionId 100+id; batch-loaded in one findAllById.
        when(mentionRepository.findAllById(any()))
                .thenReturn(List.of(mention(102L), mention(101L)));

        mvc.perform(get("/api/abuse-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].userId").value(USER_ID))
                .andExpect(jsonPath("$[0].mention.id").value(102))
                .andExpect(jsonPath("$[0].mention.permalink").value("https://x.com/p/102"))
                .andExpect(jsonPath("$[1].id").value(1))
                .andExpect(jsonPath("$[1].mention.id").value(101));
    }

    @Test
    void list_whenMentionDeleted_returnsNullMention() throws Exception {
        when(abuseReportRepository.findByUserIdOrderBySubmittedAtDesc(USER_ID))
                .thenReturn(List.of(report(2L, AbuseReport.Status.UPHELD)));
        // No mentions come back from the batch load — the mention was deleted.
        when(mentionRepository.findAllById(any())).thenReturn(List.of());

        mvc.perform(get("/api/abuse-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].mention.id").doesNotExist());
    }

    @Test
    void list_withStatus_filtersByStatus() throws Exception {
        when(abuseReportRepository.findByUserIdAndStatusOrderBySubmittedAtDesc(
                USER_ID, AbuseReport.Status.UPHELD))
                .thenReturn(List.of(report(9L, AbuseReport.Status.UPHELD)));
        when(mentionRepository.findAllById(any())).thenReturn(List.of(mention(109L)));

        mvc.perform(get("/api/abuse-reports").param("status", "UPHELD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(9))
                .andExpect(jsonPath("$[0].status").value("UPHELD"))
                .andExpect(jsonPath("$[0].mention.id").value(109));

        verify(abuseReportRepository).findByUserIdAndStatusOrderBySubmittedAtDesc(
                USER_ID, AbuseReport.Status.UPHELD);
    }

    @Test
    void list_withInvalidStatus_returns400() throws Exception {
        mvc.perform(get("/api/abuse-reports").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    private static AbuseReport report(Long id, AbuseReport.Status status) {
        return AbuseReport.builder()
                .id(id)
                .mentionId(100L + id)
                .userId(USER_ID)
                .category(AbuseReport.Category.HARASSMENT)
                .status(status)
                .submittedAt(NOW)
                .build();
    }

    private static Mention mention(Long id) {
        Mention m = new Mention();
        m.setId(id);
        m.setPlatform(Platform.X);
        m.setPostId("post_" + id);
        m.setAuthor("@user_" + id);
        m.setContent("post body " + id);
        m.setPermalink("https://x.com/p/" + id);
        return m;
    }
}
