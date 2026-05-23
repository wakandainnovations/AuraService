package com.aura.service.controller;

import com.aura.service.entity.CrisisPlan;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.ReplyDraft;
import com.aura.service.entity.User;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.LLMService;
import com.aura.service.service.SocialMediaService;
import com.aura.service.service.TopSpreaderLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MentionActionControllerTest {

    private static final Long MENTION_ID = 100L;
    private static final Long ENTITY_ID = 7L;
    private static final String ENTITY_NAME = "Galaxy Quest";
    private static final String CONTENT = "the new trailer looks great!";
    private static final String AUTHOR = "fan42";
    private static final String POST_ID = "x-abc-123";
    private static final Instant POST_DATE = Instant.parse("2026-05-20T10:00:00Z");
    private static final String USERNAME = "ops_user";
    private static final Long USER_ID = 55L;

    private static final String REPLY_PROMPT_TEMPLATE =
            "entity=[Managed Entity] post=[Paste the user's post here] sentiment=[Positive / Negative / Neutral]";
    private static final String CRISIS_PROMPT_TEMPLATE =
            "entity=[Managed Entity] crisis=[Crisis Description]";
    private static final String ALLY_DM_PROMPT_TEMPLATE =
            "entity=[Managed Entity] handle=[Ally Handle] platform=[Ally Platform] " +
                    "tier=[Ally Tier] mention=[Mention Content]";

    private LLMService llmService;
    private SocialMediaService socialMediaService;
    private MentionRepository mentionRepository;
    private ReplyDraftRepository replyDraftRepository;
    private CrisisPlanRepository crisisPlanRepository;
    private UserRepository userRepository;
    private StubSpreaderLookup spreaderLookup;

    /**
     * Hand-written test double — Mockito's inline mock maker can't mock this class on the
     * current JDK (same workaround as {@code SentimentAlertServiceTest.StubSpreaderLookup}).
     */
    static class StubSpreaderLookup extends TopSpreaderLookupService {
        private final java.util.Map<String, List<TopSpreaderLookupService.SpreaderProfile>> byKeyword = new java.util.HashMap<>();
        private final java.util.List<String> calls = new java.util.ArrayList<>();

        StubSpreaderLookup() {
            super(null, null);
        }

        void put(String keyword, List<TopSpreaderLookupService.SpreaderProfile> profiles) {
            byKeyword.put(keyword, profiles);
        }

        java.util.List<String> calls() {
            return calls;
        }

        @Override
        public List<TopSpreaderLookupService.SpreaderProfile> getSpreaderProfiles(String keyword) {
            calls.add(keyword);
            return byKeyword.getOrDefault(keyword, List.of());
        }
    }

    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        socialMediaService = mock(SocialMediaService.class);
        mentionRepository = mock(MentionRepository.class);
        replyDraftRepository = mock(ReplyDraftRepository.class);
        crisisPlanRepository = mock(CrisisPlanRepository.class);
        userRepository = mock(UserRepository.class);
        spreaderLookup = new StubSpreaderLookup();

        MentionActionController controller = new MentionActionController(
                llmService,
                socialMediaService,
                mentionRepository,
                replyDraftRepository,
                crisisPlanRepository,
                userRepository,
                spreaderLookup
        );
        ReflectionTestUtils.setField(controller, "generateReplyPrompt", REPLY_PROMPT_TEMPLATE);
        ReflectionTestUtils.setField(controller, "crisisPlanPromptTemplate", CRISIS_PROMPT_TEMPLATE);
        ReflectionTestUtils.setField(controller, "allyDmPromptTemplate", ALLY_DM_PROMPT_TEMPLATE);

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

    private Mention buildMention(Sentiment sentiment) {
        return buildMention(sentiment, new ArrayList<>());
    }

    private Mention buildMention(Sentiment sentiment, List<String> keywords) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        entity.setName(ENTITY_NAME);
        List<EntityKeyword> eks = new ArrayList<>();
        for (String kw : keywords) {
            EntityKeyword ek = new EntityKeyword();
            ek.setKeyword(kw);
            eks.add(ek);
        }
        entity.setKeywords(eks);

        Mention m = new Mention();
        m.setId(MENTION_ID);
        m.setManagedEntity(entity);
        m.setPlatform(Platform.X);
        m.setPostId(POST_ID);
        m.setContent(CONTENT);
        m.setAuthor(AUTHOR);
        m.setPostDate(POST_DATE);
        m.setSentiment(sentiment);
        m.setPermalink("https://x.com/" + AUTHOR + "/" + POST_ID);
        m.setSentimentScore((short) 80);
        return m;
    }

    @Test
    void draftReply_persistsDraftAndReturnsGeneratedText() throws Exception {
        Mention mention = buildMention(Sentiment.POSITIVE);
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention));
        when(llmService.generateReply(any())).thenReturn("Thanks so much for the kind words!");
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> {
            ReplyDraft d = inv.getArgument(0);
            d.setId(1234L);
            return d;
        });

        mvc.perform(post("/api/mentions/{id}/actions/draft-reply", MENTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mention.id").value(MENTION_ID))
                .andExpect(jsonPath("$.mention.managedEntityId").value(ENTITY_ID))
                .andExpect(jsonPath("$.mention.platform").value("X"))
                .andExpect(jsonPath("$.draftId").value(1234))
                .andExpect(jsonPath("$.generatedText").value("Thanks so much for the kind words!"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService).generateReply(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("entity=" + ENTITY_NAME);
        assertThat(prompt).contains("post=" + CONTENT);
        assertThat(prompt).contains("sentiment=POSITIVE");

        ArgumentCaptor<ReplyDraft> draftCaptor = ArgumentCaptor.forClass(ReplyDraft.class);
        verify(replyDraftRepository).save(draftCaptor.capture());
        ReplyDraft saved = draftCaptor.getValue();
        assertThat(saved.getMentionId()).isEqualTo(MENTION_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getText()).isEqualTo("Thanks so much for the kind words!");
        assertThat(saved.getStatus()).isEqualTo(ReplyDraft.Status.DRAFT);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getPostedAt()).isNull();
    }

    @Test
    void draftReply_stripsOuterQuotesFromLlmOutput() throws Exception {
        Mention mention = buildMention(Sentiment.NEGATIVE);
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention));
        when(llmService.generateReply(any())).thenReturn("Here's the reply: \"We hear you.\"");
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> {
            ReplyDraft d = inv.getArgument(0);
            d.setId(2L);
            return d;
        });

        mvc.perform(post("/api/mentions/{id}/actions/draft-reply", MENTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedText").value("We hear you."));

        ArgumentCaptor<ReplyDraft> draftCaptor = ArgumentCaptor.forClass(ReplyDraft.class);
        verify(replyDraftRepository).save(draftCaptor.capture());
        assertThat(draftCaptor.getValue().getText()).isEqualTo("We hear you.");
    }

    @Test
    void draftReply_returns404WhenMentionMissing() throws Exception {
        when(mentionRepository.findById(404L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/mentions/{id}/actions/draft-reply", 404L))
                .andExpect(status().isNotFound());

        verify(llmService, never()).generateReply(any());
        verify(replyDraftRepository, never()).save(any());
    }

    @Test
    void postReply_callsSocialMediaServiceAndMarksDraftPosted() throws Exception {
        Mention mention = buildMention(Sentiment.NEUTRAL);
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention));

        ReplyDraft draft = ReplyDraft.builder()
                .id(9L)
                .mentionId(MENTION_ID)
                .userId(USER_ID)
                .text("Hello there!")
                .status(ReplyDraft.Status.DRAFT)
                .createdAt(Instant.parse("2026-05-22T09:00:00Z"))
                .build();
        when(replyDraftRepository.findById(9L)).thenReturn(Optional.of(draft));
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));
        when(socialMediaService.postReply(eq(Platform.X), eq(POST_ID), eq("Hello there!")))
                .thenReturn("Reply posted successfully (mock)");

        String body = mapper.writeValueAsString(java.util.Map.of("draft_id", 9));

        mvc.perform(post("/api/mentions/{id}/actions/post-reply", MENTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mention.id").value(MENTION_ID))
                .andExpect(jsonPath("$.draftId").value(9))
                .andExpect(jsonPath("$.text").value("Hello there!"))
                .andExpect(jsonPath("$.postedAt").exists())
                .andExpect(jsonPath("$.result").value("Reply posted successfully (mock)"));

        verify(socialMediaService).postReply(Platform.X, POST_ID, "Hello there!");

        ArgumentCaptor<ReplyDraft> draftCaptor = ArgumentCaptor.forClass(ReplyDraft.class);
        verify(replyDraftRepository).save(draftCaptor.capture());
        ReplyDraft saved = draftCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ReplyDraft.Status.POSTED);
        assertThat(saved.getPostedAt()).isNotNull();
    }

    @Test
    void postReply_returns404WhenMentionMissing() throws Exception {
        when(mentionRepository.findById(404L)).thenReturn(Optional.empty());

        String body = mapper.writeValueAsString(java.util.Map.of("draft_id", 9));

        mvc.perform(post("/api/mentions/{id}/actions/post-reply", 404L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        verify(socialMediaService, never()).postReply(any(), any(), any());
        verify(replyDraftRepository, never()).save(any());
    }

    @Test
    void postReply_returns404WhenDraftMissing() throws Exception {
        Mention mention = buildMention(Sentiment.NEUTRAL);
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention));
        when(replyDraftRepository.findById(404L)).thenReturn(Optional.empty());

        String body = mapper.writeValueAsString(java.util.Map.of("draft_id", 404));

        mvc.perform(post("/api/mentions/{id}/actions/post-reply", MENTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        verify(socialMediaService, never()).postReply(any(), any(), any());
        verify(replyDraftRepository, never()).save(any());
    }

    @Test
    void postReply_returns404WhenDraftBelongsToDifferentMention() throws Exception {
        Mention mention = buildMention(Sentiment.NEUTRAL);
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention));

        ReplyDraft otherDraft = ReplyDraft.builder()
                .id(11L)
                .mentionId(999L)
                .userId(USER_ID)
                .text("text")
                .status(ReplyDraft.Status.DRAFT)
                .createdAt(Instant.now())
                .build();
        when(replyDraftRepository.findById(11L)).thenReturn(Optional.of(otherDraft));

        String body = mapper.writeValueAsString(java.util.Map.of("draft_id", 11));

        mvc.perform(post("/api/mentions/{id}/actions/post-reply", MENTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        verify(socialMediaService, never()).postReply(any(), any(), any());
        verify(replyDraftRepository, never()).save(any());
    }

    @Test
    void postReply_returns400WhenDraftIdMissing() throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of());

        mvc.perform(post("/api/mentions/{id}/actions/post-reply", MENTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void escalateToCrisis_persistsPlanAndReturnsIt() throws Exception {
        Mention mention = buildMention(Sentiment.NEGATIVE);
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention));
        when(llmService.generateCrisisPlan(any())).thenReturn("PLAN BODY");
        when(crisisPlanRepository.save(any(CrisisPlan.class))).thenAnswer(inv -> {
            CrisisPlan p = inv.getArgument(0);
            p.setId(77L);
            return p;
        });

        mvc.perform(post("/api/mentions/{id}/actions/escalate-to-crisis", MENTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mention.id").value(MENTION_ID))
                .andExpect(jsonPath("$.planId").value(77))
                .andExpect(jsonPath("$.plan").value("PLAN BODY"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService).generateCrisisPlan(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("entity=" + ENTITY_NAME);
        assertThat(prompt).contains("crisis=" + CONTENT);

        ArgumentCaptor<CrisisPlan> planCaptor = ArgumentCaptor.forClass(CrisisPlan.class);
        verify(crisisPlanRepository).save(planCaptor.capture());
        CrisisPlan saved = planCaptor.getValue();
        assertThat(saved.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(saved.getMentionId()).isEqualTo(MENTION_ID);
        assertThat(saved.getPlanText()).isEqualTo("PLAN BODY");
        assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void escalateToCrisis_returns404WhenMentionMissing() throws Exception {
        when(mentionRepository.findById(404L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/mentions/{id}/actions/escalate-to-crisis", 404L))
                .andExpect(status().isNotFound());

        verify(llmService, never()).generateCrisisPlan(any());
        verify(crisisPlanRepository, never()).save(any());
    }

    @Test
    void mobilizeAllies_returnsRankedPositiveSupportersWithDmTemplate() throws Exception {
        Mention mention = buildMention(Sentiment.POSITIVE, Arrays.asList("matrix", "sequel"));
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention));

        spreaderLookup.put("matrix", List.of(
                new TopSpreaderLookupService.SpreaderProfile("alice", "TWITTER", "TIER_1"),
                new TopSpreaderLookupService.SpreaderProfile("bob", "INSTAGRAM", "TIER_2"),
                new TopSpreaderLookupService.SpreaderProfile("carol", "TIKTOK", "TIER_3")
        ));
        spreaderLookup.put("sequel", List.of(
                new TopSpreaderLookupService.SpreaderProfile("dave", "REDDIT", "TIER_2")
        ));

        when(mentionRepository.countSentimentByAuthorsForEntity(eq(ENTITY_ID), any()))
                .thenReturn(Arrays.<Object[]>asList(
                        new Object[]{"alice", Sentiment.POSITIVE, 8L},
                        new Object[]{"alice", Sentiment.NEGATIVE, 1L},
                        new Object[]{"bob", Sentiment.POSITIVE, 5L},
                        new Object[]{"bob", Sentiment.NEUTRAL, 2L},
                        new Object[]{"carol", Sentiment.NEGATIVE, 4L},
                        new Object[]{"carol", Sentiment.POSITIVE, 1L},
                        new Object[]{"dave", Sentiment.POSITIVE, 3L}
                ));

        when(llmService.generateReply(any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            if (prompt.contains("handle=alice")) return "Hey alice, would love your take.";
            if (prompt.contains("handle=bob")) return "Hi bob, mind sharing this?";
            return "Hey friend.";
        });

        mvc.perform(post("/api/mentions/{id}/actions/mobilize-allies", MENTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mention.id").value(MENTION_ID))
                .andExpect(jsonPath("$.allies.length()").value(3))
                .andExpect(jsonPath("$.allies[0].globalUserId").value("alice"))
                .andExpect(jsonPath("$.allies[0].primaryPlatform").value("TWITTER"))
                .andExpect(jsonPath("$.allies[0].influenceTier").value("TIER_1"))
                .andExpect(jsonPath("$.allies[0].suggestedDm").value("Hey alice, would love your take."))
                .andExpect(jsonPath("$.allies[1].globalUserId").value("bob"))
                .andExpect(jsonPath("$.allies[2].globalUserId").value("dave"));

        verify(llmService, org.mockito.Mockito.times(3)).generateReply(any());
    }

    @Test
    void mobilizeAllies_cachesResponsePer5MinutesPerEntityMention() throws Exception {
        Mention mention = buildMention(Sentiment.POSITIVE, Arrays.asList("matrix"));
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention));
        spreaderLookup.put("matrix", List.of(
                new TopSpreaderLookupService.SpreaderProfile("alice", "TWITTER", "TIER_1")
        ));
        when(mentionRepository.countSentimentByAuthorsForEntity(eq(ENTITY_ID), any()))
                .thenReturn(Arrays.<Object[]>asList(new Object[]{"alice", Sentiment.POSITIVE, 5L}));
        when(llmService.generateReply(any())).thenReturn("hi alice");

        mvc.perform(post("/api/mentions/{id}/actions/mobilize-allies", MENTION_ID))
                .andExpect(status().isOk());
        mvc.perform(post("/api/mentions/{id}/actions/mobilize-allies", MENTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allies[0].globalUserId").value("alice"));

        assertThat(spreaderLookup.calls()).containsExactly("matrix");
        verify(llmService, org.mockito.Mockito.times(1)).generateReply(any());
    }

    @Test
    void mobilizeAllies_returns404WhenMentionMissing() throws Exception {
        when(mentionRepository.findById(404L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/mentions/{id}/actions/mobilize-allies", 404L))
                .andExpect(status().isNotFound());

        assertThat(spreaderLookup.calls()).isEmpty();
        verify(llmService, never()).generateReply(any());
    }

    @Test
    void mobilizeAllies_returnsEmptyWhenEntityHasNoKeywords() throws Exception {
        Mention mention = buildMention(Sentiment.POSITIVE, new ArrayList<>());
        when(mentionRepository.findById(MENTION_ID)).thenReturn(Optional.of(mention));

        mvc.perform(post("/api/mentions/{id}/actions/mobilize-allies", MENTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allies.length()").value(0));

        assertThat(spreaderLookup.calls()).isEmpty();
        verify(llmService, never()).generateReply(any());
    }
}
