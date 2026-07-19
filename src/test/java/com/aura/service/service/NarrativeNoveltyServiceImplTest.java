package com.aura.service.service;

import com.aura.service.dto.NarrativeNoveltyScore;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link NarrativeNoveltyServiceImpl}: the noveltyScore formula must stay within the catalog's
 * fixed [0.30, 0.45] band (Direction: Positive, Impact: +30% to +45%, read as bounds on the score
 * itself), the request-level failures that must still throw (no movie, no synopsis, unparseable JSON),
 * and the per-rating tolerance that defaults a missing/invalid/out-of-range field to 1 rather than
 * discarding an otherwise-usable response. Collaborators are mocked as interfaces ({@link LLMService},
 * {@link ManagedEntityRepository}) — never concrete classes.
 */
class NarrativeNoveltyServiceImplTest {

    private static final Long MOVIE_ID = 11L;
    private static final String PROMPT_TEMPLATE = "Synopsis: [Insert Synopsis]";

    private LLMService llmService;
    private ManagedEntityRepository entityRepository;
    private NarrativeNoveltyServiceImpl service;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        entityRepository = mock(ManagedEntityRepository.class);
        service = new NarrativeNoveltyServiceImpl(llmService, entityRepository);
        ReflectionTestUtils.setField(service, "llmPrompt", PROMPT_TEMPLATE);
    }

    @Test
    void computesNoveltyScoreWithinFixedBand() {
        when(entityRepository.findById(MOVIE_ID))
                .thenReturn(Optional.of(movieWithSynopsis("A memory thief navigates a city built from other people's dreams.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"premiseClarity": 4, "worldBuildingDistinctiveness": 5, "hookMemorability": 4, \
                "conceptualCollisionRisk": 2, "rationale": "Fresh, legible high-concept hook."}""");

        NarrativeNoveltyScore score = service.getNarrativeNovelty(MOVIE_ID);

        // worldBuild (5-1)/4*0.40 + clarity (4-1)/4*0.25 + hook (4-1)/4*0.20 + (1 - (2-1)/4)*0.15
        // = 0.40 + 0.1875 + 0.15 + 0.1125 = 0.85 -> 0.30 + 0.85*0.15 = 0.4275
        assertThat(score.getPremiseClarity()).isEqualTo(4);
        assertThat(score.getWorldBuildingDistinctiveness()).isEqualTo(5);
        assertThat(score.getRationale()).isEqualTo("Fresh, legible high-concept hook.");
        assertThat(score.getNoveltyScore()).isCloseTo(0.4275, within(1e-9));
    }

    @Test
    void minimumRatingsYieldScoreFloor() {
        when(entityRepository.findById(MOVIE_ID))
                .thenReturn(Optional.of(movieWithSynopsis("A generic hero saves a generic town.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"premiseClarity": 1, "worldBuildingDistinctiveness": 1, "hookMemorability": 1, \
                "conceptualCollisionRisk": 5, "rationale": "Entirely conventional and derivative."}""");

        NarrativeNoveltyScore score = service.getNarrativeNovelty(MOVIE_ID);

        assertThat(score.getNoveltyScore()).isCloseTo(0.30, within(1e-9));
    }

    @Test
    void maximumRatingsYieldScoreCeiling() {
        when(entityRepository.findById(MOVIE_ID))
                .thenReturn(Optional.of(movieWithSynopsis("A wholly original, richly imagined world.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"premiseClarity": 5, "worldBuildingDistinctiveness": 5, "hookMemorability": 5, \
                "conceptualCollisionRisk": 1, "rationale": "Highly distinctive and pitchable."}""");

        NarrativeNoveltyScore score = service.getNarrativeNovelty(MOVIE_ID);

        assertThat(score.getNoveltyScore()).isCloseTo(0.45, within(1e-9));
    }

    @Test
    void scoreNeverFallsOutsideFixedBandAcrossAllRatingCombinations() {
        when(entityRepository.findById(MOVIE_ID))
                .thenReturn(Optional.of(movieWithSynopsis("Some premise.")));
        for (int clarity = 1; clarity <= 5; clarity++) {
            for (int world = 1; world <= 5; world++) {
                for (int hook = 1; hook <= 5; hook++) {
                    for (int collision = 1; collision <= 5; collision++) {
                        when(llmService.generateReply(any())).thenReturn(String.format("""
                                {"premiseClarity": %d, "worldBuildingDistinctiveness": %d, "hookMemorability": %d, \
                                "conceptualCollisionRisk": %d, "rationale": "r"}""", clarity, world, hook, collision));
                        double noveltyScore = service.getNarrativeNovelty(MOVIE_ID).getNoveltyScore();
                        assertThat(noveltyScore).isBetween(0.30, 0.45);
                    }
                }
            }
        }
    }

    @Test
    void higherWorldBuildingDistinctivenessScoresHigherAllElseEqual() {
        when(entityRepository.findById(MOVIE_ID))
                .thenReturn(Optional.of(movieWithSynopsis("A")), Optional.of(movieWithSynopsis("B")));
        when(llmService.generateReply(any())).thenReturn(
                """
                {"premiseClarity": 3, "worldBuildingDistinctiveness": 5, "hookMemorability": 3, \
                "conceptualCollisionRisk": 3, "rationale": "Distinctive world."}""",
                """
                {"premiseClarity": 3, "worldBuildingDistinctiveness": 1, "hookMemorability": 3, \
                "conceptualCollisionRisk": 3, "rationale": "Generic world."}""");

        double distinctiveScore = service.getNarrativeNovelty(MOVIE_ID).getNoveltyScore();
        double genericScore = service.getNarrativeNovelty(MOVIE_ID).getNoveltyScore();

        assertThat(distinctiveScore).isGreaterThan(genericScore);
    }

    @Test
    void quotedIntegerRatingsAreAcceptedLikePlainIntegers() {
        // Regression test: despite the prompt's explicit "PLAIN INTEGERS" instruction, the LLM
        // frequently quotes ratings as strings (e.g. "4" instead of 4). The parser must tolerate this.
        when(entityRepository.findById(MOVIE_ID))
                .thenReturn(Optional.of(movieWithSynopsis("A memory thief navigates dream-built cities.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"premiseClarity": "4", "worldBuildingDistinctiveness": "5", "hookMemorability": "4", \
                "conceptualCollisionRisk": "2", "rationale": "Fresh, legible high-concept hook."}""");

        NarrativeNoveltyScore score = service.getNarrativeNovelty(MOVIE_ID);

        assertThat(score.getWorldBuildingDistinctiveness()).isEqualTo(5);
        assertThat(score.getNoveltyScore()).isCloseTo(0.4275, within(1e-9));
    }

    @Test
    void nonNumericStringRatingDefaultsToOneRatherThanThrowing() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A plot.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"premiseClarity": "high", "worldBuildingDistinctiveness": 5, "hookMemorability": 4, \
                "conceptualCollisionRisk": 2, "rationale": "Non-numeric rating."}""");

        NarrativeNoveltyScore score = service.getNarrativeNovelty(MOVIE_ID);

        assertThat(score.getPremiseClarity()).isEqualTo(1);
    }

    @Test
    void missingMovieThrowsResourceNotFound() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getNarrativeNovelty(MOVIE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void blankSynopsisThrowsIllegalArgument() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("  ")));

        assertThatThrownBy(() -> service.getNarrativeNovelty(MOVIE_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedLlmJsonThrowsInsteadOfReturningNull() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A plot.")));
        when(llmService.generateReply(any())).thenReturn("not valid json");

        assertThatThrownBy(() -> service.getNarrativeNovelty(MOVIE_ID))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void outOfRangeRatingDefaultsToOneRatherThanThrowing() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A plot.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"premiseClarity": 7, "worldBuildingDistinctiveness": 5, "hookMemorability": 4, \
                "conceptualCollisionRisk": 2, "rationale": "Out of range."}""");

        NarrativeNoveltyScore score = service.getNarrativeNovelty(MOVIE_ID);

        assertThat(score.getPremiseClarity()).isEqualTo(1);
    }

    @Test
    void missingRatingFieldDefaultsToOneRatherThanThrowing() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A plot.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"worldBuildingDistinctiveness": 5, "hookMemorability": 4, \
                "conceptualCollisionRisk": 2, "rationale": "Missing premiseClarity entirely."}""");

        NarrativeNoveltyScore score = service.getNarrativeNovelty(MOVIE_ID);

        assertThat(score.getPremiseClarity()).isEqualTo(1);
    }

    private static ManagedEntity movieWithSynopsis(String synopsis) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(MOVIE_ID);
        entity.setType("MOVIE");
        entity.setSynopsis(synopsis);
        return entity;
    }
}
