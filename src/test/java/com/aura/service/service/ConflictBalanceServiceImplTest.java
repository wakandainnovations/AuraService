package com.aura.service.service;

import com.aura.service.dto.ConflictBalanceScore;
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
 * Covers {@link ConflictBalanceServiceImpl}: the balance-score formula must stay within the catalog's
 * fixed [0.25, 0.35] band (Direction: Positive, Impact: +25% to +35%, read as bounds on the score
 * itself), the request-level failures that must still throw (no movie, no synopsis, unparseable JSON),
 * and the per-rating tolerance that defaults a missing/invalid/out-of-range field to 1 rather than
 * discarding an otherwise-usable response. Collaborators are mocked as interfaces ({@link LLMService},
 * {@link ManagedEntityRepository}) — never concrete classes.
 */
class ConflictBalanceServiceImplTest {

    private static final Long MOVIE_ID = 11L;
    private static final String PROMPT_TEMPLATE = "Synopsis: [Insert Synopsis]";

    private LLMService llmService;
    private ManagedEntityRepository entityRepository;
    private ConflictBalanceServiceImpl service;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        entityRepository = mock(ManagedEntityRepository.class);
        service = new ConflictBalanceServiceImpl(llmService, entityRepository);
        ReflectionTestUtils.setField(service, "llmPrompt", PROMPT_TEMPLATE);
    }

    @Test
    void computesBalanceScoreWithinFixedBand() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A hero faces a rival.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"protagonistPower": 3, "antagonistPower": 5, "antagonistMotivationClarity": 4, \
                "stakesEscalation": 5, "rationale": "Clear escalating threat."}""");

        ConflictBalanceScore score = service.getConflictBalance(MOVIE_ID);

        assertThat(score.getProtagonistPower()).isEqualTo(3);
        assertThat(score.getAntagonistPower()).isEqualTo(5);
        assertThat(score.getRationale()).isEqualTo("Clear escalating threat.");
        // normalized = (5-1)/4*0.5 + (4-1)/4*0.25 + (5-1)/4*0.25 = 0.9375 -> 0.25 + 0.9375*0.10 = 0.34375
        assertThat(score.getBalanceScore()).isCloseTo(0.34375, within(1e-9));
    }

    @Test
    void minimumRatingsYieldScoreFloor() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A flat, low-stakes plot.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"protagonistPower": 3, "antagonistPower": 1, "antagonistMotivationClarity": 1, \
                "stakesEscalation": 1, "rationale": "No credible antagonist."}""");

        ConflictBalanceScore score = service.getConflictBalance(MOVIE_ID);

        assertThat(score.getBalanceScore()).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void maximumAntagonistRatingsYieldScoreCeilingRegardlessOfProtagonistPower() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A weak hero, an unstoppable foe.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"protagonistPower": 1, "antagonistPower": 5, "antagonistMotivationClarity": 5, \
                "stakesEscalation": 5, "rationale": "Dominant, fully-motivated antagonist."}""");

        ConflictBalanceScore score = service.getConflictBalance(MOVIE_ID);

        assertThat(score.getBalanceScore()).isCloseTo(0.35, within(1e-9));
    }

    @Test
    void scoreNeverFallsOutsideFixedBandAcrossAllRatingCombinations() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("Some plot.")));
        for (int antagonist = 1; antagonist <= 5; antagonist++) {
            for (int motivation = 1; motivation <= 5; motivation++) {
                for (int stakes = 1; stakes <= 5; stakes++) {
                    when(llmService.generateReply(any())).thenReturn(String.format("""
                            {"protagonistPower": 3, "antagonistPower": %d, "antagonistMotivationClarity": %d, \
                            "stakesEscalation": %d, "rationale": "r"}""", antagonist, motivation, stakes));
                    double balanceScore = service.getConflictBalance(MOVIE_ID).getBalanceScore();
                    assertThat(balanceScore).isBetween(0.25, 0.35);
                }
            }
        }
    }

    @Test
    void strongAntagonistScoresHigherThanWeakAntagonistRegardlessOfProtagonistPower() {
        // Regression test for the symmetric-gap formula this replaced: |protagonist - antagonist|
        // scored "weak protagonist vs. strong antagonist" identically to "strong protagonist vs. weak
        // antagonist", contradicting the catalog decision that only antagonist strength is positive.
        when(entityRepository.findById(MOVIE_ID))
                .thenReturn(Optional.of(movieWithSynopsis("A")), Optional.of(movieWithSynopsis("B")));
        when(llmService.generateReply(any())).thenReturn(
                """
                {"protagonistPower": 2, "antagonistPower": 5, "antagonistMotivationClarity": 3, \
                "stakesEscalation": 3, "rationale": "Strong antagonist."}""",
                """
                {"protagonistPower": 5, "antagonistPower": 2, "antagonistMotivationClarity": 3, \
                "stakesEscalation": 3, "rationale": "Weak antagonist."}""");

        double strongAntagonistScore = service.getConflictBalance(MOVIE_ID).getBalanceScore();
        double weakAntagonistScore = service.getConflictBalance(MOVIE_ID).getBalanceScore();

        assertThat(strongAntagonistScore).isGreaterThan(weakAntagonistScore);
    }

    @Test
    void quotedIntegerRatingsAreAcceptedLikePlainIntegers() {
        // Regression test: despite the prompt's explicit "PLAIN INTEGERS" instruction, the LLM
        // frequently quotes ratings as strings (e.g. "4" instead of 4). The parser must tolerate this.
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A hero faces a rival.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"protagonistPower": "3", "antagonistPower": "5", "antagonistMotivationClarity": "4", \
                "stakesEscalation": "5", "rationale": "Clear escalating threat."}""");

        ConflictBalanceScore score = service.getConflictBalance(MOVIE_ID);

        assertThat(score.getAntagonistPower()).isEqualTo(5);
        assertThat(score.getBalanceScore()).isCloseTo(0.34375, within(1e-9));
    }

    @Test
    void nonNumericStringRatingDefaultsToOneRatherThanThrowing() {
        // Regression test: the model sometimes writes "NA" (or similar) instead of following its own
        // instruction to rate 1 when there's no clear antagonist. Default to 1 rather than discarding
        // an otherwise-usable response.
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A plot.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"protagonistPower": 4, "antagonistPower": "NA", "antagonistMotivationClarity": 4, \
                "stakesEscalation": 5, "rationale": "Non-numeric rating."}""");

        ConflictBalanceScore score = service.getConflictBalance(MOVIE_ID);

        assertThat(score.getAntagonistPower()).isEqualTo(1);
    }

    @Test
    void missingMovieThrowsResourceNotFound() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConflictBalance(MOVIE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void blankSynopsisThrowsIllegalArgument() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("  ")));

        assertThatThrownBy(() -> service.getConflictBalance(MOVIE_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedLlmJsonThrowsInsteadOfReturningNull() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A plot.")));
        when(llmService.generateReply(any())).thenReturn("not valid json");

        assertThatThrownBy(() -> service.getConflictBalance(MOVIE_ID))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void outOfRangeRatingDefaultsToOneRatherThanThrowing() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A plot.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"protagonistPower": 7, "antagonistPower": 5, "antagonistMotivationClarity": 4, \
                "stakesEscalation": 5, "rationale": "Out of range."}""");

        ConflictBalanceScore score = service.getConflictBalance(MOVIE_ID);

        assertThat(score.getProtagonistPower()).isEqualTo(1);
    }

    @Test
    void missingRatingFieldDefaultsToOneRatherThanThrowing() {
        when(entityRepository.findById(MOVIE_ID)).thenReturn(Optional.of(movieWithSynopsis("A plot.")));
        when(llmService.generateReply(any())).thenReturn("""
                {"antagonistPower": 5, "antagonistMotivationClarity": 4, \
                "stakesEscalation": 5, "rationale": "Missing protagonistPower entirely."}""");

        ConflictBalanceScore score = service.getConflictBalance(MOVIE_ID);

        assertThat(score.getProtagonistPower()).isEqualTo(1);
    }

    private static ManagedEntity movieWithSynopsis(String synopsis) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(MOVIE_ID);
        entity.setType("MOVIE");
        entity.setSynopsis(synopsis);
        return entity;
    }
}
