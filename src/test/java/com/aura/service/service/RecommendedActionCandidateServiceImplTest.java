package com.aura.service.service;

import com.aura.service.dto.RecommendedActionCandidate;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.EntityLanguageSpreaderSnapshot;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.MobilizeAction;
import com.aura.service.enums.RecommendedActionCategory;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.CrisisPlanRepository;
import com.aura.service.repository.EntityLanguageSpreaderSnapshotRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.MobilizeActionRepository;
import com.aura.service.repository.ReplyDraftRepository;
import com.aura.service.service.TopSpreaderLookupService.SpreaderProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers {@link RecommendedActionCandidateServiceImpl}: category/confidence tier boundaries, the
 * minimum-sample-size omission rule, the calibrated factor 46/47 calendar windows, the evangelist
 * positive-sentiment filter/tier ranking, the genre-gated candidates, and the well-formedness of
 * every produced candidate. Collaborator repositories are mocked as interfaces (never concrete
 * classes, per this project's Java 25 / Mockito constraint); {@link DashboardService} and
 * {@link TopSpreaderLookupService} are concrete classes, so per project convention they are
 * constructed for real and driven through their own (interface) dependencies - the spreader cache is
 * seeded directly via reflection to avoid needing to mock the network proxy layer underneath it.
 * {@code movies_data_collection} native queries are reached through the mockable
 * {@link MoviesDataCollectionQueryService} interface rather than a mocked
 * {@code jakarta.persistence.EntityManager}, which this project's Java 25 toolchain cannot mock (it
 * extends {@code java.lang.AutoCloseable}, a JDK-module class Mockito's inline mock maker can't
 * instrument).
 */
class RecommendedActionCandidateServiceImplTest {

    private static final Long ENTITY_ID = 1L;

    private ManagedEntityRepository entityRepository;
    private MentionRepository mentionRepository;
    private MobilizeActionRepository mobilizeActionRepository;
    private TopSpreaderLookupService spreaderLookup;
    private MoviesDataCollectionQueryService moviesDataQueryService;
    private GenreMarketingLookupService genreMarketingLookup;
    private MovieBuffLookupService movieBuffLookup;
    private ViralSeedLookupService viralSeedLookup;
    private MovieMarketingTacticsQueryService tacticsQueryService;
    private NonObviousLeverLookupService nonObviousLeverLookup;
    private PlaybookLookupService playbookLookup;
    private EntityLanguageSpreaderSnapshotRepository spreaderSnapshotRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Fixed well before every releaseDate used across this file's tests, so "not yet released" (the
    // common-tactic filler gate) holds for them by default without each test having to think about it.
    private final Clock clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
    private RecommendedActionCandidateServiceImpl service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        mentionRepository = mock(MentionRepository.class);
        mobilizeActionRepository = mock(MobilizeActionRepository.class);
        spreaderLookup = new TopSpreaderLookupService(null, new ObjectMapper());
        DashboardService dashboardService = new DashboardService(
                mentionRepository, entityRepository,
                mock(ReplyDraftRepository.class), mock(CrisisPlanRepository.class),
                mock(CheckpointRepository.class), null);
        moviesDataQueryService = mock(MoviesDataCollectionQueryService.class);
        genreMarketingLookup = mock(GenreMarketingLookupService.class);
        movieBuffLookup = mock(MovieBuffLookupService.class);
        viralSeedLookup = mock(ViralSeedLookupService.class);
        tacticsQueryService = mock(MovieMarketingTacticsQueryService.class);
        nonObviousLeverLookup = mock(NonObviousLeverLookupService.class);
        playbookLookup = mock(PlaybookLookupService.class);
        spreaderSnapshotRepository = mock(EntityLanguageSpreaderSnapshotRepository.class);

        service = new RecommendedActionCandidateServiceImpl(
                entityRepository, mentionRepository, mobilizeActionRepository, spreaderLookup, dashboardService,
                moviesDataQueryService, genreMarketingLookup, movieBuffLookup, viralSeedLookup, tacticsQueryService,
                nonObviousLeverLookup, playbookLookup, spreaderSnapshotRepository, objectMapper, clock);

        // Defaults so a test that doesn't care about a given generator isn't polluted by it.
        stubHourlyActivity(0, List.of());
        stubReleaseDayStats(List.of());
        stubBudgetComps(List.of());
        stubPeerTactics(List.of());
        stubTotalMentions(1_000L);
        when(genreMarketingLookup.getGenreReach(any())).thenReturn(null);
        when(movieBuffLookup.getMovieBuffs(anyString())).thenReturn(List.of());
        when(viralSeedLookup.getViralSeeds(anyString())).thenReturn(List.of());
        when(mobilizeActionRepository.findByEntityIdIn(any())).thenReturn(List.of());
        when(entityRepository.findByTypeAndBudgetBetweenAndIdNot(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(nonObviousLeverLookup.getNonObviousLevers(anyLong())).thenReturn(List.of());
        when(playbookLookup.getPlaybookPatterns(any(), any())).thenReturn(List.of());
        when(spreaderSnapshotRepository.findByEntityId(any())).thenReturn(List.of());
    }

    // ==================== Category threshold boundaries ====================

    @Test
    void categoryIsHighImpactExactlyAtThreshold() {
        var def = new BoxOfficeFactorCatalog.FactorDefinition(
                1, "Synthetic", BoxOfficeFactorCatalog.Direction.POSITIVE, 0.25, 0.25, BoxOfficeFactorCatalog.Role.COMPOUNDING);
        assertThat(RecommendedActionCandidateServiceImpl.categorize(def)).isEqualTo(RecommendedActionCategory.HIGH_IMPACT);
    }

    @Test
    void categoryIsMediumImpactJustBelowHighThreshold() {
        var def = new BoxOfficeFactorCatalog.FactorDefinition(
                1, "Synthetic", BoxOfficeFactorCatalog.Direction.POSITIVE, 0.249, 0.249, BoxOfficeFactorCatalog.Role.COMPOUNDING);
        assertThat(RecommendedActionCandidateServiceImpl.categorize(def)).isEqualTo(RecommendedActionCategory.MEDIUM_IMPACT);
    }

    @Test
    void categoryIsMediumImpactExactlyAtThreshold() {
        var def = new BoxOfficeFactorCatalog.FactorDefinition(
                1, "Synthetic", BoxOfficeFactorCatalog.Direction.POSITIVE, 0.12, 0.12, BoxOfficeFactorCatalog.Role.COMPOUNDING);
        assertThat(RecommendedActionCandidateServiceImpl.categorize(def)).isEqualTo(RecommendedActionCategory.MEDIUM_IMPACT);
    }

    @Test
    void categoryIsLowImpactJustBelowMediumThreshold() {
        var def = new BoxOfficeFactorCatalog.FactorDefinition(
                1, "Synthetic", BoxOfficeFactorCatalog.Direction.POSITIVE, 0.119, 0.119, BoxOfficeFactorCatalog.Role.COMPOUNDING);
        assertThat(RecommendedActionCandidateServiceImpl.categorize(def)).isEqualTo(RecommendedActionCategory.LOW_IMPACT);
    }

    // ==================== Comps confidence tier boundaries ====================

    @Test
    void compsConfidenceBelowMinSampleIsOmitted() {
        assertThat(RecommendedActionCandidateServiceImpl.compsConfidence(4)).isNull();
    }

    @Test
    void compsConfidenceAtMinSampleIsLowTier() {
        assertThat(RecommendedActionCandidateServiceImpl.compsConfidence(5)).isEqualTo(55);
    }

    @Test
    void compsConfidenceJustBelowMidTierStaysLow() {
        assertThat(RecommendedActionCandidateServiceImpl.compsConfidence(14)).isEqualTo(55);
    }

    @Test
    void compsConfidenceAtMidTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.compsConfidence(15)).isEqualTo(70);
    }

    @Test
    void compsConfidenceJustBelowHighTierStaysMid() {
        assertThat(RecommendedActionCandidateServiceImpl.compsConfidence(29)).isEqualTo(70);
    }

    @Test
    void compsConfidenceAtHighTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.compsConfidence(30)).isEqualTo(85);
    }

    // ==================== Evangelist confidence tier boundaries ====================

    @Test
    void evangelistConfidenceLowTier() {
        assertThat(RecommendedActionCandidateServiceImpl.evangelistConfidence(3)).isEqualTo(50);
    }

    @Test
    void evangelistConfidenceMidTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.evangelistConfidence(4)).isEqualTo(65);
    }

    @Test
    void evangelistConfidenceHighTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.evangelistConfidence(8)).isEqualTo(80);
    }

    // ==================== Peak-hour confidence tier boundaries ====================

    @Test
    void hourlyConfidenceBelowMinSampleIsOmitted() {
        assertThat(RecommendedActionCandidateServiceImpl.hourlyConfidence(19)).isNull();
    }

    @Test
    void hourlyConfidenceAtMinSampleIsLowTier() {
        assertThat(RecommendedActionCandidateServiceImpl.hourlyConfidence(20)).isEqualTo(50);
    }

    @Test
    void hourlyConfidenceAtMidTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.hourlyConfidence(100)).isEqualTo(65);
    }

    @Test
    void hourlyConfidenceAtHighTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.hourlyConfidence(500)).isEqualTo(80);
    }

    // ==================== Word-of-mouth confidence tier boundaries ====================

    @Test
    void wordOfMouthConfidenceBelowMinSampleIsOmitted() {
        assertThat(RecommendedActionCandidateServiceImpl.wordOfMouthConfidence(9)).isNull();
    }

    @Test
    void wordOfMouthConfidenceAtMinSampleIsLowTier() {
        assertThat(RecommendedActionCandidateServiceImpl.wordOfMouthConfidence(10)).isEqualTo(55);
    }

    @Test
    void wordOfMouthConfidenceAtMidTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.wordOfMouthConfidence(50)).isEqualTo(70);
    }

    @Test
    void wordOfMouthConfidenceAtHighTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.wordOfMouthConfidence(200)).isEqualTo(85);
    }

    // ==================== Factor 46 / 47 calibrated calendar windows ====================

    @Test
    void trailerTeaserWindowMatchesCalibratedThresholdsExactly() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 1), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        RecommendedActionCandidate candidate = findCandidate(service.buildCandidateActions(ENTITY_ID),
                "factor-46-trailer-teaser-timing");

        assertThat(candidate.windowStartDaysFromRelease()).isEqualTo(-45);
        assertThat(candidate.windowEndDaysFromRelease()).isEqualTo(-30);
        assertThat(candidate.confidencePct()).isEqualTo(90);
        assertThat(candidate.category()).isEqualTo(RecommendedActionCategory.HIGH_IMPACT);
    }

    @Test
    void firstSingleWindowMatchesCalibratedThresholdsExactly() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 1), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        RecommendedActionCandidate candidate = findCandidate(service.buildCandidateActions(ENTITY_ID),
                "factor-47-first-single-timing");

        assertThat(candidate.windowStartDaysFromRelease()).isEqualTo(-56);
        assertThat(candidate.windowEndDaysFromRelease()).isEqualTo(-42);
        assertThat(candidate.confidencePct()).isEqualTo(90);
    }

    @Test
    void noReleaseDateOmitsCalendarCandidates() {
        ManagedEntity entity = movie(null, null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("trailer-teaser"));
        assertThat(candidates).noneMatch(c -> c.candidateId().contains("first-single"));
    }

    @Test
    void alreadyReleasedMovieOmitsTrailerTeaserAndFirstSingleCandidates() {
        // Before the fixed test clock's "now" (2020-01-01) -> already released, so recommending a
        // pre-release trailer/teaser or first-single window no longer makes sense.
        ManagedEntity entity = movie(LocalDate.of(2019, 6, 1), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("trailer-teaser"));
        assertThat(candidates).noneMatch(c -> c.candidateId().contains("first-single"));
    }

    // ==================== Low online presence (absence of mention data as the signal) ====================

    // Regression coverage for the "Lord Gaaga" bug: a movie with near-zero tracked mentions and no
    // release date close enough for the calendar-based candidates to matter must still get a
    // candidate telling the marketing team to go build visibility, rather than silently producing
    // nothing the way every other engagement-driven generator correctly does when data is scarce.
    @Test
    void nearZeroMentionCountProducesLowOnlinePresenceCandidate() {
        ManagedEntity entity = movie(null, null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubTotalMentions(3L);

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate lowPresence = findCandidate(candidates, "factor-52-low-online-presence");
        assertThat(lowPresence.confidencePct()).isEqualTo(65);
        assertThat(lowPresence.supportingFacts().get(0)).contains("3").contains("25");
    }

    @Test
    void mentionCountAtThresholdOmitsLowOnlinePresenceCandidate() {
        ManagedEntity entity = movie(null, null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubTotalMentions(25L);

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("low-online-presence"));
    }

    // ==================== Genre audience reach (AuraMath, no budget required) ====================

    // AuraMath's genre-scoped audience data needs nothing but a genre - grounding a candidate for a
    // small/independent movie with no budget and no tracked mentions, unlike every comps/engagement
    // generator above.
    @Test
    void auraMathGenreReachProducesCandidateGroundedInBothFacts() {
        ManagedEntity entity = movie(null, "Action,Adventure", null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(genreMarketingLookup.getGenreReach("Action"))
                .thenReturn(new GenreMarketingLookupService.GenreReach(52_000L, "Instagram"));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate reach = findCandidate(candidates, "factor-52-genre-audience-reach");
        assertThat(reach.confidencePct()).isEqualTo(70);
        assertThat(reach.supportingFacts()).anyMatch(f -> f.contains("52,000") && f.contains("Action"));
        assertThat(reach.supportingFacts()).anyMatch(f -> f.contains("Instagram"));
    }

    @Test
    void auraMathGenreReachUsesOnlyPrimaryGenreToken() {
        ManagedEntity entity = movie(null, "Action,Adventure,Psychedelic", null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        service.buildCandidateActions(ENTITY_ID);

        verify(genreMarketingLookup).getGenreReach("Action");
    }

    @Test
    void auraMathUnavailableOmitsGenreReachCandidate() {
        ManagedEntity entity = movie(null, "Action", null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(genreMarketingLookup.getGenreReach("Action")).thenReturn(null);

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("genre-audience-reach"));
    }

    @Test
    void blankGenreOmitsGenreReachCandidateWithoutCallingAuraMath() {
        ManagedEntity entity = movie(null, null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("genre-audience-reach"));
        verify(genreMarketingLookup, never()).getGenreReach(anyString());
    }

    // "AuraMath" is this platform's internal upstream provider name and must never leak into a
    // supportingFacts string - those flow verbatim into the LLM prompt and, on any LLM failure, straight
    // into the user-facing reason text via fallbackActions.
    @Test
    void supportingFactsNeverNameTheInternalUpstreamProvider() {
        ManagedEntity entity = movie(null, "Action,Adventure", null, null);
        entity.setKeywords(List.of(new EntityKeyword("lordgaaga", null, null, null, null, null)));
        seedSpreaders("lordgaaga", List.of());
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(genreMarketingLookup.getGenreReach("Action"))
                .thenReturn(new GenreMarketingLookupService.GenreReach(52_000L, "Instagram"));
        when(movieBuffLookup.getMovieBuffs("lordgaaga")).thenReturn(List.of(
                new MovieBuffLookupService.MovieBuff("buff1", "TIER_1", "x", null)));
        when(viralSeedLookup.getViralSeeds("lordgaaga")).thenReturn(List.of(
                new ViralSeedLookupService.ViralSeed("seed1", "x", null)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates)
                .flatExtracting(RecommendedActionCandidate::supportingFacts)
                .noneMatch(f -> f.contains("AuraMath"));
    }

    // ==================== Movie-buff outreach (AuraMath) ====================

    @Test
    void movieBuffsProduceOutreachCandidateWithTierBreakdown() {
        ManagedEntity entity = movie(null, null, null, null);
        entity.setKeywords(List.of(new EntityKeyword("lordgaaga", null, null, null, null, null)));
        seedSpreaders("lordgaaga", List.of());
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(movieBuffLookup.getMovieBuffs("lordgaaga")).thenReturn(List.of(
                new MovieBuffLookupService.MovieBuff("u1", "TIER_1", "x", "https://twitter.com/u1"),
                new MovieBuffLookupService.MovieBuff("u2", "TIER_3", null, null),
                new MovieBuffLookupService.MovieBuff("u3", "TIER_2", "instagram", null)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate outreach = findCandidate(candidates, "factor-53-movie-buff-outreach");
        assertThat(outreach.confidencePct()).isEqualTo(65);
        assertThat(outreach.supportingFacts()).anyMatch(f -> f.contains("3 movie buff"));
        assertThat(outreach.supportingFacts()).anyMatch(f -> f.contains("2 of these are Tier-1/2"));
        // exampleHandles ranked by tier (TIER_1 first, then TIER_2, then TIER_3) - real handles a
        // marketing team can actually reach out to, not just a count.
        assertThat(outreach.exampleHandles()).containsExactly("u1", "u3", "u2");
        assertThat(outreach.supportingFacts()).anyMatch(f -> f.contains("u1") && f.contains("u3") && f.contains("u2"));
        // relevantUsers is the fuller "View Details" roster (same tier ranking as exampleHandles),
        // carrying platform/profile link only for the accounts AuraMath actually supplied them for.
        assertThat(outreach.relevantUsers()).containsExactly(
                new com.aura.service.dto.RecommendedActionUser("u1", "x", "https://twitter.com/u1"),
                new com.aura.service.dto.RecommendedActionUser("u3", "instagram", null),
                new com.aura.service.dto.RecommendedActionUser("u2", null, null));
    }

    @Test
    void noMovieBuffsFoundOmitsOutreachCandidate() {
        ManagedEntity entity = movie(null, null, null, null);
        entity.setKeywords(List.of(new EntityKeyword("lordgaaga", null, null, null, null, null)));
        seedSpreaders("lordgaaga", List.of());
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("movie-buff-outreach"));
    }

    @Test
    void noKeywordsOmitsMovieBuffCandidateWithoutCallingAuraMath() {
        ManagedEntity entity = movie(null, null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("movie-buff-outreach"));
        verify(movieBuffLookup, never()).getMovieBuffs(anyString());
    }

    // ==================== Viral seed outreach (AuraMath) ====================

    @Test
    void viralSeedsProduceOutreachCandidateWithTopPlatform() {
        ManagedEntity entity = movie(null, null, null, null);
        entity.setKeywords(List.of(new EntityKeyword("lordgaaga", null, null, null, null, null)));
        seedSpreaders("lordgaaga", List.of());
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(viralSeedLookup.getViralSeeds("lordgaaga")).thenReturn(List.of(
                new ViralSeedLookupService.ViralSeed("u1", "instagram", "https://instagram.com/u1"),
                new ViralSeedLookupService.ViralSeed("u2", "x", null)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate outreach = findCandidate(candidates, "factor-53-viral-seed-outreach");
        assertThat(outreach.confidencePct()).isEqualTo(65);
        assertThat(outreach.supportingFacts()).anyMatch(f -> f.contains("2 viral-seed account"));
        assertThat(outreach.supportingFacts()).anyMatch(f -> f.contains("instagram"));
        // exampleHandles preserves AuraMath's own top-ranked ordering (u1 before u2) - real handles,
        // not just a count.
        assertThat(outreach.exampleHandles()).containsExactly("u1", "u2");
        assertThat(outreach.supportingFacts()).anyMatch(f -> f.contains("u1") && f.contains("u2"));
        // relevantUsers is the fuller "View Details" roster (same AuraMath ranking), carrying platform
        // and a profile link only for the account AuraMath actually supplied one for.
        assertThat(outreach.relevantUsers()).containsExactly(
                new com.aura.service.dto.RecommendedActionUser("u1", "instagram", "https://instagram.com/u1"),
                new com.aura.service.dto.RecommendedActionUser("u2", "x", null));
    }

    @Test
    void noViralSeedsFoundOmitsOutreachCandidate() {
        ManagedEntity entity = movie(null, null, null, null);
        entity.setKeywords(List.of(new EntityKeyword("lordgaaga", null, null, null, null, null)));
        seedSpreaders("lordgaaga", List.of());
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("viral-seed-outreach"));
    }

    // ==================== Genre-gated candidates ====================

    @Test
    void blankGenreOmitsReleaseDayAndBudgetComps() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "", "Kannada", 1_000_000.0);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("release-day"));
        assertThat(candidates).noneMatch(c -> c.candidateId().contains("screen-count"));
        assertThat(candidates).noneMatch(c -> c.candidateId().contains("pa-commitments"));
    }

    @Test
    void presentGenreProducesBudgetCompsCandidatesWhenSampleSufficient() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", 1_000_000.0);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubBudgetComps(List.<Object[]>of(new Object[]{18L, 420_000_000.0}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate screenCount = findCandidate(candidates, "factor-87-screen-count-allocation");
        RecommendedActionCandidate paCommitments = findCandidate(candidates, "factor-88-pa-commitments");
        assertThat(screenCount.confidencePct()).isEqualTo(70); // 18 comps -> mid tier
        assertThat(paCommitments.confidencePct()).isEqualTo(70);
        assertThat(screenCount.supportingFacts()).anyMatch(f -> f.contains("18") && f.contains("420,000,000"));
    }

    @Test
    void budgetCompsBelowMinSampleProducesNoCandidate() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", 1_000_000.0);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubBudgetComps(List.<Object[]>of(new Object[]{4L, 100.0}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("screen-count"));
        assertThat(candidates).noneMatch(c -> c.candidateId().contains("pa-commitments"));
    }

    // Regression coverage: a movie with no budget on file (the common case for the small/independent
    // productions this platform's actual data skews toward) used to skip budget-comps candidates
    // entirely. It should now still get them, scoped to genre+language across every budget tier.
    @Test
    void noBudgetOnFileStillProducesBudgetCompsCandidatesAcrossAllBudgets() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubBudgetComps(List.<Object[]>of(new Object[]{9L, 2_900_000.0}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate screenCount = findCandidate(candidates, "factor-87-screen-count-allocation");
        assertThat(screenCount.confidencePct()).isEqualTo(55); // 9 comps -> low tier
        assertThat(screenCount.supportingFacts()).anyMatch(f -> f.contains("no budget on file"));
        verify(moviesDataQueryService).findGenreLanguageBudgetComps("Action", "Kannada", 0.0, Double.MAX_VALUE);
    }

    @Test
    void releaseDayCandidateRecommendsBestPerformingDayOverActualDay() {
        // 2026-06-05 is a Friday -> Postgres DOW 5; Saturday (DOW 6) outperforms it and should be
        // recommended instead, with real comparable titles cited.
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubReleaseDayStats(List.of(
                new Object[]{5, 20L, 500_000_000.0, List.of("Movie A")},
                new Object[]{6, 99L, 999_000_000.0, List.of("Movie B", "Movie C")}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate releaseDay = findCandidate(candidates, "factor-61-release-day");
        assertThat(releaseDay.confidencePct()).isEqualTo(85); // 99 comps -> high tier
        assertThat(releaseDay.supportingFacts().get(0))
                .contains("Saturday").contains("Friday").contains("99").contains("999,000,000")
                .contains("Movie B").contains("Movie C");
    }

    @Test
    void releaseDayCandidateConfirmsWhenActualDayIsAlreadyBest() {
        // 2026-06-05 is a Friday -> Postgres DOW 5, and it's the best-performing day here.
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubReleaseDayStats(List.of(
                new Object[]{5, 20L, 999_000_000.0, List.of()},
                new Object[]{6, 20L, 500_000_000.0, List.of()}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate releaseDay = findCandidate(candidates, "factor-61-release-day");
        assertThat(releaseDay.supportingFacts().get(0)).contains("already matches").contains("Friday");
    }

    @Test
    void releaseDayCandidateOmittedOnceMovieHasAlreadyReleased() {
        // Before the fixed test clock's "now" (2020-01-01) -> already released.
        ManagedEntity entity = movie(LocalDate.of(2019, 6, 7), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubReleaseDayStats(List.<Object[]>of(new Object[]{5, 20L, 500_000_000.0, List.of()}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("release-day"));
    }

    // ==================== Peer marketing-tactic candidates ====================

    @Test
    void noPeerTacticsProducesNoPeerTacticCandidate() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubPeerTactics(List.of());

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("peer-tactic-"));
    }

    @Test
    void singlePeerTacticProducesLowTierGroundedCandidate() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubPeerTactics(List.<Object[]>of(tacticRow(
                "KD – The Devil", "2026", "Trailer & Video Marketing", "Teaser Trailers",
                "High-octane teaser focused on atmospheric grit.")));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate candidate = findCandidate(candidates, "peer-tactic-trailer-video-marketing-teaser-trailers");
        assertThat(candidate.category()).isEqualTo(RecommendedActionCategory.MEDIUM_IMPACT);
        assertThat(candidate.confidencePct()).isEqualTo(50); // 1 comp -> low tier
        assertThat(candidate.factorName()).isEqualTo("Teaser Trailers");
        assertThat(candidate.exampleHandles()).isEmpty();
        assertThat(candidate.supportingFacts()).anyMatch(f ->
                f.contains("KD – The Devil") && f.contains("2026") && f.contains("High-octane teaser focused on atmospheric grit."));
        verify(tacticsQueryService).findPeerTactics("Action", "Kannada");
    }

    @Test
    void peerTacticConfidenceReachesMidTierAtThreeDistinctMovies() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubPeerTactics(List.of(
                tacticRow("Movie A", "2024", "Trailer & Video Marketing", "Teaser Trailers", "Tactic A"),
                tacticRow("Movie B", "2025", "Trailer & Video Marketing", "Teaser Trailers", "Tactic B"),
                tacticRow("Movie C", "2026", "Trailer & Video Marketing", "Teaser Trailers", "Tactic C")));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate candidate = findCandidate(candidates, "peer-tactic-trailer-video-marketing-teaser-trailers");
        assertThat(candidate.confidencePct()).isEqualTo(62); // 3 comps -> mid tier
    }

    @Test
    void peerTacticConfidenceReachesHighTierAtSixDistinctMovies() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(tacticRow("Movie " + i, "202" + i, "Trailer & Video Marketing", "Teaser Trailers", "Tactic " + i));
        }
        stubPeerTactics(rows);

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate candidate = findCandidate(candidates, "peer-tactic-trailer-video-marketing-teaser-trailers");
        assertThat(candidate.confidencePct()).isEqualTo(75); // 6 comps -> high tier
    }

    @Test
    void peerTacticCandidateCapsExamplesAtThreeMostRecent() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubPeerTactics(List.of(
                tacticRow("Oldest Movie", "2020", "Trailer & Video Marketing", "Teaser Trailers", "Tactic Old"),
                tacticRow("Movie B", "2022", "Trailer & Video Marketing", "Teaser Trailers", "Tactic B"),
                tacticRow("Movie C", "2024", "Trailer & Video Marketing", "Teaser Trailers", "Tactic C"),
                tacticRow("Newest Movie", "2026", "Trailer & Video Marketing", "Teaser Trailers", "Tactic New")));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate candidate = findCandidate(candidates, "peer-tactic-trailer-video-marketing-teaser-trailers");
        // Summary fact + 3 per-movie facts (capped), newest first, oldest dropped.
        assertThat(candidate.supportingFacts()).hasSize(4);
        assertThat(candidate.supportingFacts()).noneMatch(f -> f.contains("Oldest Movie"));
        assertThat(candidate.supportingFacts()).anyMatch(f -> f.contains("Newest Movie"));
    }

    @Test
    void differentClassificationBucketsProduceSeparateCandidates() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubPeerTactics(List.of(
                tacticRow("Movie A", "2026", "Trailer & Video Marketing", "Teaser Trailers", "Tactic A"),
                tacticRow("Movie B", "2026", "On-Ground Events", "Star Appearances", "Tactic B")));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates.stream().filter(c -> c.candidateId().startsWith("peer-tactic-")).toList()).hasSize(2);
    }

    // ==================== Common-tactic filtering ====================

    private static List<Object[]> sixMovieCommonTacticBucket() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(tacticRow("Movie " + i, "202" + i, "Trailer & Video Marketing", "Teaser Trailers", "Tactic " + i));
        }
        return rows;
    }

    @Test
    void isCommonTacticRequiresMinimumPeerSampleDespiteFullPrevalence() {
        assertThat(RecommendedActionCandidateServiceImpl.isCommonTactic(4, 4)).isFalse();
        assertThat(RecommendedActionCandidateServiceImpl.isCommonTactic(5, 5)).isTrue();
    }

    @Test
    void isCommonTacticRequiresPrevalenceThreshold() {
        assertThat(RecommendedActionCandidateServiceImpl.isCommonTactic(6, 10)).isFalse(); // 60%
        assertThat(RecommendedActionCandidateServiceImpl.isCommonTactic(7, 10)).isTrue(); // 70%
    }

    @Test
    void tacticSignalKeywordsStripsGenericMarketingVocabulary() {
        Set<String> keywords = RecommendedActionCandidateServiceImpl.tacticSignalKeywords("Teaser Release");
        assertThat(keywords).containsExactly("teaser");
    }

    @Test
    void tacticSignalKeywordsIsEmptyWhenEveryTokenIsGenericOrShort() {
        assertThat(RecommendedActionCandidateServiceImpl.tacticSignalKeywords("Digital Marketing Campaign")).isEmpty();
    }

    @Test
    void commonTacticWithheldWhenPlanAlreadyHasEnoughActions() {
        // Near Diwali 2026 -> trailer-teaser-timing + first-single-timing + holiday-proximity = 3
        // grounded actions already, so the low-inventory fallback should not fire.
        ManagedEntity entity = movie(LocalDate.of(2026, 11, 5), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubPeerTactics(sixMovieCommonTacticBucket());

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("peer-tactic-"));
    }

    @Test
    void commonTacticSurfacedAsFallbackWhenPlanIsThinAndNotYetReleased() {
        // Far from any holiday -> only trailer-teaser-timing + first-single-timing = 2 grounded
        // actions, below COMMON_TACTIC_FILLER_MIN_ACTIONS, and the fixed test clock is well before
        // this releaseDate, so the common tactic should be added back as a fallback.
        ManagedEntity entity = movie(LocalDate.of(2026, 2, 17), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubPeerTactics(sixMovieCommonTacticBucket());

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate candidate = findCandidate(candidates, "peer-tactic-trailer-video-marketing-teaser-trailers");
        assertThat(candidate.confidencePct()).isEqualTo(75); // 6 comps -> high tier, unaffected by the filler path
    }

    @Test
    void commonTacticWithheldWhenTrackedPostsAlreadyShowItHappened() {
        ManagedEntity entity = movie(LocalDate.of(2026, 2, 17), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubPeerTactics(sixMovieCommonTacticBucket());
        when(mentionRepository.existsByManagedEntityIdAndContentContainingIgnoreCase(ENTITY_ID, "teaser"))
                .thenReturn(true);

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("peer-tactic-"));
    }

    @Test
    void commonTacticWithheldOnceMovieHasAlreadyReleased() {
        Clock pastReleaseClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        RecommendedActionCandidateServiceImpl releasedMovieService = new RecommendedActionCandidateServiceImpl(
                entityRepository, mentionRepository, mobilizeActionRepository, spreaderLookup,
                new DashboardService(mentionRepository, entityRepository, mock(ReplyDraftRepository.class),
                        mock(CrisisPlanRepository.class), mock(CheckpointRepository.class), null),
                moviesDataQueryService, genreMarketingLookup, movieBuffLookup, viralSeedLookup, tacticsQueryService,
                nonObviousLeverLookup, playbookLookup, spreaderSnapshotRepository, objectMapper, pastReleaseClock);
        // Far from any holiday and thin plan (2 grounded actions), but releaseDate is before the
        // fixed clock's "now" -> already released, so no fallback regardless of low inventory.
        ManagedEntity entity = movie(LocalDate.of(2019, 2, 17), "Action", "Kannada", null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubPeerTactics(sixMovieCommonTacticBucket());

        List<RecommendedActionCandidate> candidates = releasedMovieService.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("peer-tactic-"));
    }

    // ==================== Holiday proximity ====================

    @Test
    void releaseNearHolidayProducesHolidayCandidate() {
        // Diwali 2026-11-08; release 3 days before.
        ManagedEntity entity = movie(LocalDate.of(2026, 11, 5), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate holiday = findCandidate(candidates, "factor-61-holiday-proximity");
        assertThat(holiday.confidencePct()).isEqualTo(90);
        assertThat(holiday.windowLabel()).isEqualTo("Release week");
        assertThat(holiday.supportingFacts().get(0)).contains("Diwali");
    }

    @Test
    void releaseFarFromAnyHolidayOmitsHolidayCandidate() {
        ManagedEntity entity = movie(LocalDate.of(2026, 2, 17), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("holiday-proximity"));
    }

    @Test
    void alreadyReleasedMovieOmitsHolidayCandidate() {
        // Diwali 2019-10-27ish is irrelevant here - what matters is the releaseDate falling before the
        // fixed test clock's "now" (2020-01-01), i.e. already released.
        ManagedEntity entity = movie(LocalDate.of(2019, 11, 5), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("holiday-proximity"));
    }

    @Test
    void romanceGenreNearGenericHolidayButFarFromValentinesOmitsHolidayCandidate() {
        // Republic Day 2026-01-26; a Romance movie should not get this generic holiday recommended -
        // it's far from Valentine's Day, its own genre-appropriate window.
        ManagedEntity entity = movie(LocalDate.of(2026, 1, 26), "Romance", null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("holiday-proximity"));
    }

    @Test
    void romanceGenreNearValentinesDayProducesGenreAppropriateHolidayCandidate() {
        ManagedEntity entity = movie(LocalDate.of(2026, 2, 12), "Romance,Drama", null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate holiday = findCandidate(candidates, "factor-61-holiday-proximity");
        assertThat(holiday.supportingFacts().get(0)).contains("Valentine's Day");
    }

    // ==================== Evangelist positive-sentiment filter and tier ranking ====================

    @Test
    void evangelistCandidateFiltersToPredominantlyPositiveAccountsOnly() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), null, null, null);
        entity.setKeywords(List.of(new EntityKeyword("MovieKeyword", null, null, null, null, null)));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        seedSpreaders("MovieKeyword", List.of(
                new SpreaderProfile("posUser", "X", "TIER_1", 500L, "https://x.com/posUser"), // pos>neg && pos>=neu -> qualifies
                new SpreaderProfile("tiedUser", "X", "TIER_2", 500L, null),     // pos==neu, pos>neg -> qualifies (>=)
                new SpreaderProfile("negUser", "X", "TIER_3", 500L, null),      // pos<neg -> excluded
                new SpreaderProfile("neuHeavyUser", "X", "TIER_1", 500L, null))); // pos<neu -> excluded

        when(mentionRepository.countSentimentByAuthorsForEntity(eq(ENTITY_ID), any())).thenReturn(List.of(
                new Object[]{"posUser", Sentiment.POSITIVE, 5L},
                new Object[]{"posUser", Sentiment.NEGATIVE, 1L},
                new Object[]{"tiedUser", Sentiment.POSITIVE, 2L},
                new Object[]{"tiedUser", Sentiment.NEUTRAL, 2L},
                new Object[]{"negUser", Sentiment.POSITIVE, 1L},
                new Object[]{"negUser", Sentiment.NEGATIVE, 3L},
                new Object[]{"neuHeavyUser", Sentiment.POSITIVE, 1L},
                new Object[]{"neuHeavyUser", Sentiment.NEUTRAL, 4L}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate evangelist = findCandidate(candidates, "factor-17-evangelist-mobilization");
        assertThat(evangelist.confidencePct()).isEqualTo(50); // 2 qualifying accounts (posUser, tiedUser) -> low tier (1-3)
        assertThat(evangelist.supportingFacts().get(0)).contains("2 positive-sentiment accounts");
        // exampleHandles excludes the disqualified accounts (negUser, neuHeavyUser) entirely and ranks
        // the qualifying ones by total_views (tied at 500 here), tiebroken by positive-mention count
        // (posUser has 5, tiedUser has 2) - real handles to mobilize, not just a count.
        assertThat(evangelist.exampleHandles()).containsExactly("posUser", "tiedUser");
        // relevantUsers is the fuller "View Details" roster (same ranking/exclusions), carrying a
        // profile link only for the account AuraMath actually supplied one for.
        assertThat(evangelist.relevantUsers()).containsExactly(
                new com.aura.service.dto.RecommendedActionUser("posUser", "X", "https://x.com/posUser"),
                new com.aura.service.dto.RecommendedActionUser("tiedUser", "X", null));
    }

    @Test
    void noQualifyingPositiveAccountsOmitsEvangelistCandidate() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), null, null, null);
        entity.setKeywords(List.of(new EntityKeyword("MovieKeyword", null, null, null, null, null)));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        seedSpreaders("MovieKeyword", List.of(new SpreaderProfile("negUser", "X", "TIER_1", 0L, null)));
        when(mentionRepository.countSentimentByAuthorsForEntity(eq(ENTITY_ID), any())).thenReturn(List.<Object[]>of(
                new Object[]{"negUser", Sentiment.NEGATIVE, 5L}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("evangelist-mobilization"));
    }

    @Test
    void noKeywordsOmitsEvangelistCandidateWithoutCallingSpreaderLookup() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("evangelist-mobilization"));
    }

    @Test
    void allyMobilizationLiftFactAppearsWithEnoughComparableHistory() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), "Action", "Kannada", 1_000_000.0);
        entity.setKeywords(List.of(new EntityKeyword("MovieKeyword", null, null, null, null, null)));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        seedSpreaders("MovieKeyword", List.of(new SpreaderProfile("posUser", "X", "TIER_1", 0L, null)));
        when(mentionRepository.countSentimentByAuthorsForEntity(eq(ENTITY_ID), any())).thenReturn(List.<Object[]>of(
                new Object[]{"posUser", Sentiment.POSITIVE, 5L}));

        List<ManagedEntity> comparable = List.of(
                movieWithId(101L, "Action", "Kannada", 1_100_000.0),
                movieWithId(102L, "Action", "Kannada", 1_100_000.0),
                movieWithId(103L, "Action", "Kannada", 1_100_000.0));
        when(entityRepository.findByTypeAndBudgetBetweenAndIdNot(eq("MOVIE"), any(), any(), eq(ENTITY_ID)))
                .thenReturn(comparable);

        List<MobilizeAction> events = List.of(
                mobilizeEvent(101L, Instant.parse("2025-01-01T00:00:00Z")),
                mobilizeEvent(102L, Instant.parse("2025-02-01T00:00:00Z")),
                mobilizeEvent(103L, Instant.parse("2025-03-01T00:00:00Z")));
        when(mobilizeActionRepository.findByEntityIdIn(any())).thenReturn(events);

        for (MobilizeAction event : events) {
            Instant t = event.getCreatedAt();
            when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                    event.getEntityId(), t.minus(7, ChronoUnit.DAYS), t)).thenReturn(10L);
            when(mentionRepository.countByManagedEntityIdAndPostDateBetween(
                    event.getEntityId(), t, t.plus(7, ChronoUnit.DAYS))).thenReturn(30L);
        }

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate evangelist = findCandidate(candidates, "factor-17-evangelist-mobilization");
        assertThat(evangelist.supportingFacts()).anyMatch(f -> f.contains("3.0x") && f.contains("3"));
    }

    // ==================== Peak engagement hours ====================

    @Test
    void peakHoursCandidateOmittedBelowMinActiveUserSample() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubHourlyActivity(19, List.<Object[]>of(new Object[]{18, 19L}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("peak-engagement-hours"));
    }

    @Test
    void peakHoursCandidateListsTopHoursByActiveUsers() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubHourlyActivity(150, List.of(
                new Object[]{9, 50L},
                new Object[]{20, 70L},
                new Object[]{21, 30L}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate peakHours = findCandidate(candidates, "factor-52-peak-engagement-hours");
        assertThat(peakHours.confidencePct()).isEqualTo(65); // 150 active users -> mid tier
        assertThat(peakHours.supportingFacts().get(0)).contains("20:00").contains("09:00").contains("21:00");
    }

    // ==================== Post-day-1 word-of-mouth (Factor 91) ====================

    @Test
    void wordOfMouthCandidateOmittedWhenNoReleaseDate() {
        ManagedEntity entity = movie(null, null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("organic-word-of-mouth"));
        verify(mentionRepository, never()).countByManagedEntityIdAndPostDateBetween(any(), any(), any());
    }

    @Test
    void wordOfMouthCandidateOmittedBelowMinMentionSample() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubWordOfMouth(9, 5, 2);

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().contains("organic-word-of-mouth"));
    }

    @Test
    void wordOfMouthCandidateUsesFactor91WindowAndGroundedFacts() {
        ManagedEntity entity = movie(LocalDate.of(2026, 6, 5), null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubWordOfMouth(100, 70, 20);

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        RecommendedActionCandidate wordOfMouth = findCandidate(candidates, "factor-91-organic-word-of-mouth");
        assertThat(wordOfMouth.confidencePct()).isEqualTo(70); // 100 mentions -> mid tier
        assertThat(wordOfMouth.windowStartDaysFromRelease()).isEqualTo(7);
        assertThat(wordOfMouth.windowEndDaysFromRelease()).isEqualTo(28);
        assertThat(wordOfMouth.supportingFacts().get(0))
                .contains("100 mentions")
                .contains("day-7-to-day-28")
                .contains("70.0% positive")
                .contains("20.0% negative");
    }

    @Test
    void wordOfMouthCandidateQueriesExactFactor91Window() {
        LocalDate releaseDate = LocalDate.of(2026, 6, 5);
        ManagedEntity entity = movie(releaseDate, null, null, null);
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        stubWordOfMouth(50, 25, 25);

        service.buildCandidateActions(ENTITY_ID);

        Instant expectedStart = releaseDate.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant expectedEnd = releaseDate.plusDays(29).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        verify(mentionRepository).countByManagedEntityIdAndPostDateBetween(ENTITY_ID, expectedStart, expectedEnd);
    }

    // ==================== Window label formatting ====================

    @Test
    void windowLabelStraddlingReleaseIsReleaseWeek() {
        assertThat(RecommendedActionCandidateServiceImpl.buildWindowLabel(0, 0)).isEqualTo("Release week");
        assertThat(RecommendedActionCandidateServiceImpl.buildWindowLabel(-3, 3)).isEqualTo("Release week");
    }

    @Test
    void windowLabelBeforeReleaseInWholeWeeksFormatsAsWeeks() {
        assertThat(RecommendedActionCandidateServiceImpl.buildWindowLabel(-56, -42)).isEqualTo("6-8 weeks before release");
    }

    @Test
    void windowLabelBeforeReleaseInDaysFallsBackToDays() {
        assertThat(RecommendedActionCandidateServiceImpl.buildWindowLabel(-45, -30)).isEqualTo("30-45 days before release");
    }

    @Test
    void windowLabelAfterReleaseFormatsAsWeeks() {
        assertThat(RecommendedActionCandidateServiceImpl.buildWindowLabel(14, 14)).isEqualTo("2 weeks after release");
    }

    // ==================== Well-formedness of every produced candidate ====================

    @Test
    void everyProducedCandidateHasNonNullFieldsAndAtLeastOneFact() {
        ManagedEntity entity = movie(LocalDate.of(2026, 11, 5), "Action", "Kannada", 1_000_000.0);
        entity.setKeywords(List.of(new EntityKeyword("MovieKeyword", null, null, null, null, null)));
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        stubBudgetComps(List.<Object[]>of(new Object[]{40L, 300_000_000.0}));
        stubReleaseDayStats(List.<Object[]>of(
                new Object[]{postgresDow(entity.getReleaseDate()), 40L, 300_000_000.0}));
        stubHourlyActivity(600, List.<Object[]>of(new Object[]{10, 600L}));

        seedSpreaders("MovieKeyword", List.of(new SpreaderProfile("posUser", "X", "TIER_1", 0L, null)));
        when(mentionRepository.countSentimentByAuthorsForEntity(eq(ENTITY_ID), any())).thenReturn(List.<Object[]>of(
                new Object[]{"posUser", Sentiment.POSITIVE, 5L}));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).isNotEmpty();
        for (RecommendedActionCandidate candidate : candidates) {
            assertThat(candidate.candidateId()).isNotBlank();
            assertThat(candidate.factorName()).isNotBlank();
            assertThat(candidate.category()).isNotNull();
            assertThat(candidate.confidencePct()).isBetween(0, 100);
            assertThat(candidate.windowLabel()).isNotBlank();
            assertThat(candidate.supportingFacts()).isNotEmpty();
        }
    }

    // ==================== Missing entity ====================

    @Test
    void missingEntityThrows() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.buildCandidateActions(ENTITY_ID))
                .isInstanceOf(com.aura.service.exception.ResourceNotFoundException.class);
    }

    // ==================== Non-obvious lever (AuraMath F5) ====================

    @Test
    void nonObviousLever_findingAtOrAboveQValueBar_neverProducesCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(movie(null, null, null, null)));
        when(nonObviousLeverLookup.getNonObviousLevers(ENTITY_ID)).thenReturn(List.of(
                new NonObviousLeverLookupService.LeverFinding(
                        "trailer_before_friday", "HIGHER_IN_OVERPERFORMERS", 0.02, 0.10, 40)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("nonobvious-lever-"));
    }

    @Test
    void nonObviousLever_qualifyingFinding_carriesExactFieldsUnmodified() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(movie(null, null, null, null)));
        when(nonObviousLeverLookup.getNonObviousLevers(ENTITY_ID)).thenReturn(List.of(
                new NonObviousLeverLookupService.LeverFinding(
                        "trailer_before_friday", "HIGHER_IN_OVERPERFORMERS", 0.0041, 0.031, 57)));

        RecommendedActionCandidate candidate = findCandidate(service.buildCandidateActions(ENTITY_ID),
                "nonobvious-lever-trailer-before-friday");

        assertThat(candidate.supportingFacts()).isEmpty();
        RecommendedActionCandidate.StatisticalEvidence evidence = candidate.statisticalEvidence();
        assertThat(evidence).isNotNull();
        assertThat(evidence.featureName()).isEqualTo("trailer_before_friday");
        assertThat(evidence.direction()).isEqualTo("HIGHER_IN_OVERPERFORMERS");
        assertThat(evidence.pValue()).isEqualTo(0.0041);
        assertThat(evidence.fdrQValue()).isEqualTo(0.031);
        assertThat(evidence.nEntities()).isEqualTo(57L);
        assertThat(evidence.patternSequence()).isNull();
    }

    // ==================== Playbook sequence (AuraMath F7) ====================

    @Test
    void playbook_entityMissingIndustryOrLanguage_neverProducesCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(movie(null, null, "Hindi", null)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("playbook-sequence-"));
        verifyNoInteractions(playbookLookup);
    }

    @Test
    void playbook_patternAtOrAboveQValueBar_neverProducesCandidate() {
        ManagedEntity entity = movie(null, null, "Hindi", null);
        entity.setIndustry("Bollywood");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(playbookLookup.getPlaybookPatterns("Bollywood", "Hindi")).thenReturn(List.of(
                new PlaybookLookupService.PlaybookPattern(
                        List.of("TEASER", "TRAILER"), 30, 4, 0.02, 0.10, 25)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("playbook-sequence-"));
    }

    @Test
    void playbook_qualifyingPattern_carriesExactFieldsUnmodified() {
        ManagedEntity entity = movie(null, null, "Hindi", null);
        entity.setIndustry("Bollywood");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        List<String> sequence = List.of("CAST_ANNOUNCEMENT", "TEASER", "TRAILER");
        when(playbookLookup.getPlaybookPatterns("Bollywood", "Hindi")).thenReturn(List.of(
                new PlaybookLookupService.PlaybookPattern(sequence, 30, 4, 0.0018, 0.024, 63)));

        RecommendedActionCandidate candidate = findCandidate(service.buildCandidateActions(ENTITY_ID),
                "playbook-sequence-bollywood-hindi");

        assertThat(candidate.supportingFacts()).isEmpty();
        RecommendedActionCandidate.StatisticalEvidence evidence = candidate.statisticalEvidence();
        assertThat(evidence).isNotNull();
        assertThat(evidence.patternSequence()).containsExactlyElementsOf(sequence);
        assertThat(evidence.supportTopTier()).isEqualTo(30L);
        assertThat(evidence.supportBottomTier()).isEqualTo(4L);
        assertThat(evidence.fdrQValue()).isEqualTo(0.024);
        assertThat(evidence.nEntities()).isEqualTo(63L);
        assertThat(evidence.pValue()).isNull();
        assertThat(evidence.featureName()).isNull();
    }

    @Test
    void playbook_multipleQualifyingPatternsForSameCohort_getDistinctCandidateIds() {
        ManagedEntity entity = movie(null, null, "Hindi", null);
        entity.setIndustry("Bollywood");
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));
        when(playbookLookup.getPlaybookPatterns("Bollywood", "Hindi")).thenReturn(List.of(
                new PlaybookLookupService.PlaybookPattern(List.of("TEASER", "TRAILER"), 30, 4, 0.001, 0.02, 63),
                new PlaybookLookupService.PlaybookPattern(List.of("MUSIC_LAUNCH", "PROMO_EVENT"), 22, 6, 0.004, 0.05, 40)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        List<String> playbookIds = candidates.stream()
                .map(RecommendedActionCandidate::candidateId)
                .filter(id -> id.startsWith("playbook-sequence-"))
                .toList();
        assertThat(playbookIds).containsExactlyInAnyOrder(
                "playbook-sequence-bollywood-hindi", "playbook-sequence-bollywood-hindi-2");
    }

    // ==================== Top-spreader language-coverage gap ====================

    @Test
    void topSpreaderGap_noBudgetOnFile_neverProducesCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(movie(null, null, "Tamil", null)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("top-spreader-gap-"));
        verifyNoInteractions(spreaderSnapshotRepository);
    }

    @Test
    void topSpreaderGap_undisclosedBudgetSentinel_neverProducesCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(movie(null, null, "Tamil", 404.0)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("top-spreader-gap-"));
        verifyNoInteractions(spreaderSnapshotRepository);
    }

    @Test
    void topSpreaderGap_noOwnSnapshot_neverProducesCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(movie(null, null, "Tamil", 10_000_000.0)));
        when(spreaderSnapshotRepository.findByEntityId(ENTITY_ID)).thenReturn(List.of());

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("top-spreader-gap-"));
    }

    @Test
    void topSpreaderGap_noComparableMovieHasRealBudget_neverProducesCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(movie(null, null, "Tamil", 10_000_000.0)));
        when(spreaderSnapshotRepository.findByEntityId(ENTITY_ID))
                .thenReturn(List.of(spreaderSnapshot(ENTITY_ID, "Tamil", spreaderProfiles(2))));
        // Every "comparable" movie in range has an undisclosed (404 sentinel) or missing budget - none
        // is a real budget to compare against.
        when(entityRepository.findByTypeAndBudgetBetweenAndIdNot(eq("MOVIE"), anyDouble(), anyDouble(), eq(ENTITY_ID)))
                .thenReturn(List.of(movieWithId(2L, null, "Tamil", 404.0), movieWithId(3L, null, "Tamil", null)));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("top-spreader-gap-"));
        verify(spreaderSnapshotRepository, never()).findByEntityIdInAndLanguageIgnoreCase(any(), any());
    }

    @Test
    void topSpreaderGap_shortfallBelowThreshold_neverProducesCandidate() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(movie(null, null, "Tamil", 10_000_000.0)));
        when(spreaderSnapshotRepository.findByEntityId(ENTITY_ID))
                .thenReturn(List.of(spreaderSnapshot(ENTITY_ID, "Tamil", spreaderProfiles(2))));
        when(entityRepository.findByTypeAndBudgetBetweenAndIdNot(eq("MOVIE"), anyDouble(), anyDouble(), eq(ENTITY_ID)))
                .thenReturn(List.of(movieWithId(2L, null, "Tamil", 11_000_000.0)));
        // Shortfall of 2 (4 - 2) is below SPREADER_GAP_MIN_ABSOLUTE_SHORTFALL (3).
        when(spreaderSnapshotRepository.findByEntityIdInAndLanguageIgnoreCase(List.of(2L), "Tamil"))
                .thenReturn(List.of(spreaderSnapshot(2L, "Tamil", spreaderProfiles(4))));

        List<RecommendedActionCandidate> candidates = service.buildCandidateActions(ENTITY_ID);

        assertThat(candidates).noneMatch(c -> c.candidateId().startsWith("top-spreader-gap-"));
    }

    @Test
    void topSpreaderGap_meaningfulShortfall_producesCandidateCitingComparableMovieAndNewOutreachTargets() {
        when(entityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(movie(null, null, "Tamil", 10_000_000.0)));
        List<SpreaderProfile> ownProfiles = spreaderProfiles(2); // spreader-0, spreader-1
        when(spreaderSnapshotRepository.findByEntityId(ENTITY_ID))
                .thenReturn(List.of(spreaderSnapshot(ENTITY_ID, "Tamil", ownProfiles)));
        when(entityRepository.findByTypeAndBudgetBetweenAndIdNot(eq("MOVIE"), anyDouble(), anyDouble(), eq(ENTITY_ID)))
                .thenReturn(List.of(
                        movieWithId(2L, null, "Tamil", 11_000_000.0),
                        movieWithId(3L, null, "Tamil", 12_000_000.0)));
        when(entityRepository.findById(2L)).thenReturn(Optional.of(movieWithId(2L, null, "Tamil", 11_000_000.0)));
        when(entityRepository.findById(3L)).thenReturn(Optional.of(namedMovie(3L, "Peer Movie", "Tamil", 12_000_000.0)));
        // Peer Movie (id 3) has 15 spreaders (spreader-0..14), the other comp only 4 - Peer Movie is the
        // best-covered comp and is the one the candidate should cite by name.
        when(spreaderSnapshotRepository.findByEntityIdInAndLanguageIgnoreCase(List.of(2L, 3L), "Tamil"))
                .thenReturn(List.of(
                        spreaderSnapshot(2L, "Tamil", spreaderProfiles(4)),
                        spreaderSnapshot(3L, "Tamil", spreaderProfiles(15))));

        RecommendedActionCandidate candidate = findCandidate(service.buildCandidateActions(ENTITY_ID),
                "top-spreader-gap-tamil");

        assertThat(candidate.category()).isEqualTo(RecommendedActionCategory.MEDIUM_IMPACT);
        assertThat(candidate.supportingFacts().get(0))
                .contains("Peer Movie")
                .contains("15")
                .contains("Tamil")
                .contains("2");
        // spreader-0/spreader-1 already talk about this movie; the outreach roster must exclude them
        // and only offer genuinely new prospects, ranked by reach (spreader-14 has the most views).
        assertThat(candidate.exampleHandles()).doesNotContainAnyElementsOf(
                ownProfiles.stream().map(SpreaderProfile::globalUserId).toList());
        assertThat(candidate.exampleHandles()).containsExactly("spreader-14", "spreader-13", "spreader-12");
        assertThat(candidate.relevantUsers()).hasSize(13); // 15 comp spreaders minus 2 already-own overlaps
    }

    @Test
    void spreaderGapConfidenceLowTier() {
        assertThat(RecommendedActionCandidateServiceImpl.spreaderGapConfidence(1)).isEqualTo(50);
    }

    @Test
    void spreaderGapConfidenceMidTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.spreaderGapConfidence(3)).isEqualTo(65);
    }

    @Test
    void spreaderGapConfidenceHighTierBoundary() {
        assertThat(RecommendedActionCandidateServiceImpl.spreaderGapConfidence(6)).isEqualTo(80);
    }

    @Test
    void hasRealBudget_nullZeroNegativeAndSentinel_areAllTreatedAsNoBudget() {
        assertThat(RecommendedActionCandidateServiceImpl.hasRealBudget(null)).isFalse();
        assertThat(RecommendedActionCandidateServiceImpl.hasRealBudget(0.0)).isFalse();
        assertThat(RecommendedActionCandidateServiceImpl.hasRealBudget(-5.0)).isFalse();
        assertThat(RecommendedActionCandidateServiceImpl.hasRealBudget(404.0)).isFalse();
        assertThat(RecommendedActionCandidateServiceImpl.hasRealBudget(10_000_000.0)).isTrue();
    }

    // ==================== Helpers ====================

    private static ManagedEntity movie(LocalDate releaseDate, String genre, String language, Double budget) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(ENTITY_ID);
        entity.setType("MOVIE");
        entity.setReleaseDate(releaseDate);
        entity.setGenre(genre);
        entity.setLanguage(language);
        entity.setBudget(budget);
        return entity;
    }

    private static ManagedEntity movieWithId(Long id, String genre, String language, Double budget) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(id);
        entity.setType("MOVIE");
        entity.setGenre(genre);
        entity.setLanguage(language);
        entity.setBudget(budget);
        return entity;
    }

    private static ManagedEntity namedMovie(Long id, String name, String language, Double budget) {
        ManagedEntity entity = movieWithId(id, null, language, budget);
        entity.setName(name);
        return entity;
    }

    /** {@code count} distinct SpreaderProfiles ("spreader-0".."spreader-{count-1}"), each with strictly
     *  increasing totalViews so ranking-by-reach ordering is deterministic in tests. */
    private static List<SpreaderProfile> spreaderProfiles(int count) {
        List<SpreaderProfile> profiles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            profiles.add(new SpreaderProfile("spreader-" + i, "TWITTER", null, 1000L + i, null));
        }
        return profiles;
    }

    private EntityLanguageSpreaderSnapshot spreaderSnapshot(Long entityId, String language, List<SpreaderProfile> profiles) {
        EntityLanguageSpreaderSnapshot snapshot = new EntityLanguageSpreaderSnapshot();
        snapshot.setEntityId(entityId);
        snapshot.setLanguage(language);
        snapshot.setSpreaderCount(profiles.size());
        try {
            snapshot.setSpreadersJson(objectMapper.writeValueAsString(profiles));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        snapshot.setGeneratedAt(Instant.now());
        return snapshot;
    }

    private static MobilizeAction mobilizeEvent(Long entityId, Instant createdAt) {
        return MobilizeAction.builder()
                .id(entityId)
                .mentionId(1L)
                .entityId(entityId)
                .userId(1L)
                .allyCount(5)
                .createdAt(createdAt)
                .build();
    }

    private static int postgresDow(LocalDate date) {
        return date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY ? 0 : date.getDayOfWeek().getValue();
    }

    private void seedSpreaders(String keyword, List<SpreaderProfile> profiles) {
        @SuppressWarnings("unchecked")
        com.aura.service.proxy.TtlCache<List<SpreaderProfile>> cache =
                (com.aura.service.proxy.TtlCache<List<SpreaderProfile>>)
                        ReflectionTestUtils.getField(spreaderLookup, "profileCache");
        cache.put(keyword, profiles, java.time.Duration.ofMinutes(10).toNanos());
    }

    private void stubHourlyActivity(long totalActiveUsers, List<Object[]> hourlyRows) {
        when(mentionRepository.countActiveUsersByHour(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class), isNull(), isNull(), isNull()))
                .thenReturn(hourlyRows);
        when(mentionRepository.countDistinctActiveUsers(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class), isNull(), isNull(), isNull()))
                .thenReturn(totalActiveUsers);
        when(mentionRepository.countActiveUsersByDayAndHour(
                eq(ENTITY_ID), any(Instant.class), any(Instant.class), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
    }

    private void stubReleaseDayStats(List<Object[]> rows) {
        when(moviesDataQueryService.findReleaseDayOfWeekStats(any(), any())).thenReturn(rows);
    }

    private void stubBudgetComps(List<Object[]> rows) {
        when(moviesDataQueryService.findGenreLanguageBudgetComps(any(), any(), anyDouble(), anyDouble()))
                .thenReturn(rows);
    }

    private void stubPeerTactics(List<Object[]> rows) {
        when(tacticsQueryService.findPeerTactics(any(), any())).thenReturn(rows);
    }

    private static Object[] tacticRow(String movieName, String releaseYear, String mainClassification,
                                       String subClassification, String tacticText) {
        return new Object[]{movieName, releaseYear, mainClassification, subClassification, tacticText};
    }

    private void stubTotalMentions(long total) {
        when(mentionRepository.countByManagedEntityId(any())).thenReturn(total);
    }

    private void stubWordOfMouth(long total, long positive, long negative) {
        when(mentionRepository.countByManagedEntityIdAndPostDateBetween(eq(ENTITY_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(total);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                eq(ENTITY_ID), eq(Sentiment.POSITIVE), any(Instant.class), any(Instant.class)))
                .thenReturn(positive);
        when(mentionRepository.countByManagedEntityIdAndSentimentAndPostDateBetween(
                eq(ENTITY_ID), eq(Sentiment.NEGATIVE), any(Instant.class), any(Instant.class)))
                .thenReturn(negative);
    }

    private static RecommendedActionCandidate findCandidate(List<RecommendedActionCandidate> candidates, String candidateId) {
        return candidates.stream()
                .filter(c -> c.candidateId().equals(candidateId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate with id " + candidateId + " in " +
                        candidates.stream().map(RecommendedActionCandidate::candidateId).toList()));
    }
}
