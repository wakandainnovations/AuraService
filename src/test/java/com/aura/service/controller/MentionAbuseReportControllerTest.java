package com.aura.service.controller;

import com.aura.service.abuse.AbuseReportDispatcher;
import com.aura.service.abuse.InstagramAbuseReportStrategy;
import com.aura.service.abuse.RedditAbuseReportStrategy;
import com.aura.service.abuse.XAbuseReportStrategy;
import com.aura.service.abuse.YoutubeAbuseReportStrategy;
import com.aura.service.entity.AbuseReport;
import com.aura.service.entity.Mention;
import com.aura.service.entity.User;
import com.aura.service.enums.Platform;
import com.aura.service.repository.AbuseReportRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.AbuseReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MentionAbuseReportControllerTest {

    private static final Long MENTION_ID = 100L;
    private static final String USERNAME = "ops_user";
    private static final Long USER_ID = 55L;
    private static final Instant NOW = Instant.parse("2026-05-31T12:00:00Z");

    private MentionRepository mentionRepository;
    private UserRepository userRepository;
    private AbuseReportRepository abuseReportRepository;

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mentionRepository = mock(MentionRepository.class);
        userRepository = mock(UserRepository.class);
        abuseReportRepository = mock(AbuseReportRepository.class);

        AbuseReportDispatcher dispatcher = new AbuseReportDispatcher(List.of(
                new XAbuseReportStrategy(),
                new RedditAbuseReportStrategy(),
                new YoutubeAbuseReportStrategy(),
                new InstagramAbuseReportStrategy()
        ));
        AbuseReportService service = new AbuseReportService(
                abuseReportRepository,
                mentionRepository,
                userRepository,
                dispatcher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        MentionAbuseReportController controller = new MentionAbuseReportController(service);

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
    void reportAbuse_persistsReportAndReturnsIt() throws Exception {
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention(Platform.X)));
        when(abuseReportRepository.save(any(AbuseReport.class))).thenAnswer(inv -> {
            AbuseReport r = inv.getArgument(0);
            r.setId(4242L);
            return r;
        });

        String body = mapper.writeValueAsString(Map.of(
                "category", "HARASSMENT",
                "notes", "repeated targeted abuse"));

        mvc.perform(post("/api/mentions/{id}/report-abuse", MENTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4242))
                .andExpect(jsonPath("$.mentionId").value(MENTION_ID))
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.category").value("HARASSMENT"))
                .andExpect(jsonPath("$.notes").value("repeated targeted abuse"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.externalRef").value("x-mod-4242"))
                .andExpect(jsonPath("$.submittedAt").exists());

        ArgumentCaptor<AbuseReport> captor = ArgumentCaptor.forClass(AbuseReport.class);
        verify(abuseReportRepository).save(captor.capture());
        AbuseReport saved = captor.getValue();
        assertThat(saved.getMentionId()).isEqualTo(MENTION_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getCategory()).isEqualTo(AbuseReport.Category.HARASSMENT);
        assertThat(saved.getNotes()).isEqualTo("repeated targeted abuse");
        assertThat(saved.getStatus()).isEqualTo(AbuseReport.Status.SUBMITTED);
        assertThat(saved.getSubmittedAt()).isEqualTo(NOW);
        // Dispatcher routes by platform and stamps the external ticket reference.
        assertThat(saved.getExternalRef()).isEqualTo("x-mod-4242");
    }

    @Test
    void reportAbuse_persistsReportWithoutNotes() throws Exception {
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention(Platform.REDDIT)));
        when(abuseReportRepository.save(any(AbuseReport.class))).thenAnswer(inv -> {
            AbuseReport r = inv.getArgument(0);
            r.setId(77L);
            return r;
        });

        String body = mapper.writeValueAsString(Map.of("category", "IMPERSONATION"));

        mvc.perform(post("/api/mentions/{id}/report-abuse", MENTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("IMPERSONATION"))
                .andExpect(jsonPath("$.externalRef").value("reddit-rpt-77"));

        ArgumentCaptor<AbuseReport> captor = ArgumentCaptor.forClass(AbuseReport.class);
        verify(abuseReportRepository).save(captor.capture());
        assertThat(captor.getValue().getNotes()).isNull();
    }

    @Test
    void reportAbuse_returns404WhenMentionMissing() throws Exception {
        when(mentionRepository.findById(404L)).thenReturn(Optional.empty());

        String body = mapper.writeValueAsString(Map.of("category", "MISINFORMATION"));

        mvc.perform(post("/api/mentions/{id}/report-abuse", 404L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        verify(abuseReportRepository, never()).save(any());
    }

    @Test
    void reportAbuse_returns400WhenCategoryMissing() throws Exception {
        String body = mapper.writeValueAsString(Map.of("notes", "no category here"));

        mvc.perform(post("/api/mentions/{id}/report-abuse", MENTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(abuseReportRepository, never()).save(any());
    }

    @Test
    void listForMention_returnsReportsNewestFirst() throws Exception {
        when(mentionRepository.existsById(MENTION_ID)).thenReturn(true);
        when(abuseReportRepository.findByMentionIdOrderBySubmittedAtDesc(MENTION_ID))
                .thenReturn(List.of(report(11L, AbuseReport.Status.UPHELD),
                        report(10L, AbuseReport.Status.SUBMITTED)));

        mvc.perform(get("/api/mentions/{id}/abuse-reports", MENTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[0].status").value("UPHELD"))
                .andExpect(jsonPath("$[1].id").value(10))
                .andExpect(jsonPath("$[1].status").value("SUBMITTED"));
    }

    @Test
    void listForMention_returns404WhenMentionMissing() throws Exception {
        when(mentionRepository.existsById(404L)).thenReturn(false);

        mvc.perform(get("/api/mentions/{id}/abuse-reports", 404L))
                .andExpect(status().isNotFound());

        verify(abuseReportRepository, never()).findByMentionIdOrderBySubmittedAtDesc(any());
    }

    private static AbuseReport report(Long id, AbuseReport.Status status) {
        return AbuseReport.builder()
                .id(id)
                .mentionId(MENTION_ID)
                .userId(USER_ID)
                .category(AbuseReport.Category.HARASSMENT)
                .status(status)
                .submittedAt(NOW)
                .build();
    }

    private static Mention mention(Platform platform) {
        Mention m = new Mention();
        m.setId(MENTION_ID);
        m.setPlatform(platform);
        m.setPostId("post_" + MENTION_ID);
        return m;
    }
}
