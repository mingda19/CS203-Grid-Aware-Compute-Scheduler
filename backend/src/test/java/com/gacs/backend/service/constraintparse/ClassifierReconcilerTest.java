package com.gacs.backend.service.constraintparse;

import com.gacs.backend.service.allocation.ConstraintType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the classifier and the rule-based parser disagree, and what the operator is asked.
 *
 * <p>The rule being tested is that neither source wins. Everything here is about a disagreement
 * becoming a question with both readings in it, rather than one side quietly overwriting the other
 * - because the parser is exact about values and blind to phrasings it has no reader for, while the
 * classifier is the opposite, and nothing in the system knows which one is right on a given message.
 */
class ClassifierReconcilerTest {

    private static final LocalDateTime SIX_PM = LocalDateTime.of(2026, 11, 13, 18, 0);
    private static final Set<ConstraintType> ASKED = Set.of(ConstraintType.values());

    private static ParsedConstraints parsed(LocalDateTime deadline, Integer flexibility) {
        return new ParsedConstraints(null, deadline, flexibility, null, null,
                List.of(), List.of(), List.of(), List.of());
    }

    private static ParsedConstraints nothing() {
        return parsed(null, null);
    }

    private static ConstraintGuardrail.Accepted accepted(ConstraintType type, double probability) {
        return new ConstraintGuardrail.Accepted(
                ClassifiedConstraint.of(type, probability, "whatever the text said", 0, 22), null, false);
    }

    @Test
    void bothAgreeAConstraintIsThere_NoQuestion() {
        var result = ClassifierReconciler.reconcile(parsed(SIX_PM, null),
                List.of(accepted(ConstraintType.DEADLINE, 0.98)), ASKED);

        assertThat(result.disagreements()).isEmpty();
        // A deadline is a field the form already has, so it is applied, not suggested separately.
        assertThat(result.suggestions()).isEmpty();
    }

    @Test
    void bothAgreeNothingIsThere_NoQuestion() {
        assertThat(ClassifierReconciler.reconcile(nothing(), List.of(), ASKED).disagreements()).isEmpty();
    }

    @Test
    void classifierSeesADeadlineTheParserCouldNotRead_AsksTheOperatorToSetIt() {
        // The realistic case: a phrasing the parser has no reader for. Only the operator can supply
        // the value, so the question is "set it or leave it out", not a choice between two values.
        var result = ClassifierReconciler.reconcile(nothing(),
                List.of(accepted(ConstraintType.DEADLINE, 0.93)), ASKED);

        assertThat(result.disagreements()).singleElement().satisfies(d -> {
            assertThat(d.field()).isEqualTo(ConstraintField.DEADLINE);
            assertThat(d.parsedValue()).isNull();
            assertThat(d.probability()).isEqualTo(0.93);
            assertThat(d.options()).extracting(ClassifierReconciler.Option::id)
                    .containsExactly("ENTER_VALUE", "IGNORE");
        });
    }

    @Test
    void parserReadADeadlineTheClassifierDoesNotSee_AsksWhetherToKeepIt() {
        var result = ClassifierReconciler.reconcile(parsed(SIX_PM, null), List.of(), ASKED);

        assertThat(result.disagreements()).singleElement().satisfies(d -> {
            assertThat(d.field()).isEqualTo(ConstraintField.DEADLINE);
            assertThat(d.parsedValue()).contains("18:00");
            assertThat(d.options()).extracting(ClassifierReconciler.Option::id)
                    .containsExactly("KEEP", "DROP");
        });
    }

    @Test
    void theValueInQuestionIsNamedInBothTheQuestionAndTheOptions() {
        // The operator is choosing whether to keep a specific value, so it has to be in front of
        // them. An option reading "Keep it" with the value only in the question is a worse choice.
        var result = ClassifierReconciler.reconcile(parsed(SIX_PM, null), List.of(), ASKED);

        ClassifierReconciler.Disagreement d = result.disagreements().getFirst();
        assertThat(d.question()).contains("2026-11-13T18:00");
        assertThat(d.options().getFirst().label()).contains("2026-11-13T18:00");
    }

    @Test
    void aProbabilityIsNotInventedForATypeTheClassifierNeverReported() {
        // Null, not 0.0: the score for a type that fell below the floor is not carried this far, and
        // a zero here would read as "the classifier scored this at zero", which is a measurement
        // nobody made.
        var result = ClassifierReconciler.reconcile(parsed(SIX_PM, null), List.of(), ASKED);

        assertThat(result.disagreements()).singleElement()
                .extracting(ClassifierReconciler.Disagreement::probability).isNull();
    }

    @Test
    void aTypeTheClassifierWasNeverAskedAbout_IsNotADisagreement() {
        // "The classifier does not think there is a deadline" and "nothing asked it" look identical
        // in the accepted list and mean opposite things. Only the first is a question.
        var result = ClassifierReconciler.reconcile(parsed(SIX_PM, 4), List.of(),
                Set.of(ConstraintType.FLEXIBILITY_WINDOW));

        assertThat(result.disagreements()).singleElement()
                .extracting(ClassifierReconciler.Disagreement::field)
                .isEqualTo(ConstraintField.FLEXIBILITY_WINDOW_HOURS);
    }

    @Test
    void withNothingAsked_ThereIsNothingToReconcile() {
        assertThat(ClassifierReconciler.reconcile(parsed(SIX_PM, 4), List.of(), Set.of()).disagreements())
                .isEmpty();
    }

    @Test
    void bothFieldsCanDisagreeAtOnce() {
        var result = ClassifierReconciler.reconcile(parsed(SIX_PM, null),
                List.of(accepted(ConstraintType.FLEXIBILITY_WINDOW, 0.91)), ASKED);

        assertThat(result.disagreements()).extracting(ClassifierReconciler.Disagreement::field)
                .containsExactlyInAnyOrder(ConstraintField.DEADLINE, ConstraintField.FLEXIBILITY_WINDOW_HOURS);
    }

    @Test
    void aDailyWindowHasNoFormFieldSoItIsASuggestionNotADisagreement() {
        // The parser produces no absolute-window field at all, so the classifier is the only source
        // and there is nothing to disagree with. It still has to be confirmed, but as a suggestion.
        var result = ClassifierReconciler.reconcile(nothing(),
                List.of(accepted(ConstraintType.ABSOLUTE_TIME_WINDOW, 0.87)), ASKED);

        assertThat(result.disagreements()).isEmpty();
        assertThat(result.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo(ConstraintType.ABSOLUTE_TIME_WINDOW);
            assertThat(s.probability()).isEqualTo(0.87);
        });
    }

    @Test
    void pairConstraintsAreSuggestionsAndCarryTheJobTheyBind() {
        ConstraintGuardrail.Accepted bound = new ConstraintGuardrail.Accepted(
                ClassifiedConstraint.of(ConstraintType.DEPENDENCY, 0.92, "after nightly-etl", 0, 17),
                404L, false);

        var result = ClassifierReconciler.reconcile(nothing(), List.of(bound), ASKED);

        assertThat(result.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo(ConstraintType.DEPENDENCY);
            assertThat(s.referencedJobId()).isEqualTo(404L);
        });
    }

    @Test
    void aSuggestionInTheReviewBandCarriesItsNeedForConfirmation() {
        ConstraintGuardrail.Accepted uncertain = new ConstraintGuardrail.Accepted(
                ClassifiedConstraint.of(ConstraintType.ABSOLUTE_TIME_WINDOW, 0.44, "overnight", 0, 9),
                null, true);

        assertThat(ClassifierReconciler.reconcile(nothing(), List.of(uncertain), ASKED).suggestions())
                .singleElement().extracting(ClassifierReconciler.Suggestion::needsConfirmation).isEqualTo(true);
    }
}
