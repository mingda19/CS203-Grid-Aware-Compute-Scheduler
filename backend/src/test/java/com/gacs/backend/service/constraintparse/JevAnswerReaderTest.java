package com.gacs.backend.service.constraintparse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.service.allocation.ConstraintType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader's rules, checked against responses recorded from the live API.
 *
 * <p>The fixtures in {@code src/test/resources/jev} are verbatim responses to the production
 * request, saved rather than invented. That matters for every threshold here: a made-up fixture
 * would let a rule pass against the numbers it was written for instead of the numbers the model
 * actually returns. The probabilities are quoted in each test so a fixture that is re-recorded
 * against a new model version shows up as a named disagreement rather than a silent pass.
 *
 * <p>No network and no key: the recording step is separate and deliberate.
 */
class JevAnswerReaderTest {

    private static final List<JobReferenceResolver.Candidate> CANDIDATES = List.of(
            new JobReferenceResolver.Candidate(100, "alpha-train"),
            new JobReferenceResolver.Candidate(101, "render-batch"),
            new JobReferenceResolver.Candidate(102, "nightly-etl"));

    /** The messages the fixtures were recorded for, so each test cites its own input. */
    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("deadline-only", "Finish by 6pm tomorrow."),
            Map.entry("flexibility-only", "Happy for this to start up to 4 hours late."),
            Map.entry("daily-window-only", "Only ever run this overnight, between 11pm and 5am."),
            Map.entry("deadline-and-window",
                    "Overnight only, every night, and the whole thing has to be done before Monday."),
            Map.entry("precedence", "This must finish before render-batch starts."),
            Map.entry("dependency", "Run it after the ETL job is done."),
            Map.entry("dependency-explicit", "Only start this once alpha-train has finished."),
            Map.entry("mutual-exclusion", "This can never run at the same time as nightly-etl."),
            Map.entry("synchronized-finish", "It has to finish together with render-batch."),
            Map.entry("no-constraint", "Needs 16 GPUs and 400GB of scratch disk."));

    private final JevAnswerReader reader = new JevAnswerReader(0.3);

    @Test
    void deadlineOnly_ProposesOnlyDeadline() {
        // deadline 0.99, every other type at or below 0.13
        assertThat(typesFor("deadline-only")).containsExactly(ConstraintType.DEADLINE);
    }

    @Test
    void flexibilityOnly_ProposesOnlyFlexibility() {
        // flexibility 0.94, every other type at or below 0.09
        assertThat(typesFor("flexibility-only")).containsExactly(ConstraintType.FLEXIBILITY_WINDOW);
    }

    @Test
    void dailyWindow_SuppressesTheDeadlineItsClosingHourLooksLike() {
        // daily window 0.87, deadline 0.61 - the window's end hour reading as a finish-by time
        assertThat(typesFor("daily-window-only")).containsExactly(ConstraintType.ABSOLUTE_TIME_WINDOW);
    }

    @Test
    void windowAndRealDeadline_KeepsBoth() {
        // daily window 0.79, deadline 0.97 - strong enough to stand on its own, so it is not the
        // window's closing hour and both constraints are real
        assertThat(typesFor("deadline-and-window"))
                .containsExactlyInAnyOrder(ConstraintType.DEADLINE, ConstraintType.ABSOLUTE_TIME_WINDOW);
    }

    @Test
    void precedence_SuppressesWeakDeadlineAndSubsumedExclusion() {
        // ordering 0.87, deadline 0.63, mutual exclusion 0.38: without the rules this message
        // would ask the operator to confirm three constraints when it states one
        assertThat(typesFor("precedence")).containsExactly(ConstraintType.PRECEDENCE);
    }

    @Test
    void thisJobFirst_ReadsAsPrecedence() {
        // ordering 0.87, this-job-first 0.89 - "this must finish before render-batch starts"
        assertThat(typesFor("precedence")).containsExactly(ConstraintType.PRECEDENCE);
    }

    @Test
    void otherJobFirst_ReadsAsDependency() {
        // ordering 0.94, this-job-first 0.08. The same ordering question fires on both directions;
        // without the direction question this would be stored as a precedence and run backwards.
        assertThat(typesFor("dependency")).containsExactly(ConstraintType.DEPENDENCY);
    }

    @Test
    void dependency_SurvivesAStrongCompetingExclusionReading() {
        // ordering 0.95, this-job-first 0.06, mutual exclusion 0.60 - the highest exclusion bleed
        // measured. Precedence outscores it, so the exclusion is dropped as subsumed, not confirmed.
        assertThat(typesFor("dependency-explicit")).containsExactly(ConstraintType.DEPENDENCY);
    }

    @Test
    void anUncertainDirectionCannotBeAppliedSilently() {
        // A direction answer at 0.5 is a coin flip between the two readings, and getting it wrong
        // inverts the operator's intent while still producing a plausible-looking schedule. The
        // certainty caps the proposal's probability, so it lands under applyAbove and is confirmed.
        JevAnswers coinFlip = withDirection("precedence", 0.5);
        List<ClassifiedConstraint> read = reader.read(coinFlip, MESSAGES.get("precedence"), CANDIDATES);

        assertThat(read).singleElement().satisfies(proposal ->
                assertThat(proposal.probability()).isLessThanOrEqualTo(0.5));
        assertThat(new ConstraintGuardrail(0.7, 0.3)
                .review(MESSAGES.get("precedence"), read, Set.of(ConstraintType.values()), 999L, CANDIDATES)
                .accepted()).singleElement()
                .extracting(ConstraintGuardrail.Accepted::needsConfirmation).isEqualTo(true);
    }

    @Test
    void aConfidentDirectionIsAppliedWithoutAsking() {
        List<ClassifiedConstraint> read = read("dependency");

        assertThat(new ConstraintGuardrail(0.7, 0.3)
                .review(MESSAGES.get("dependency"), read, Set.of(ConstraintType.values()), 999L, CANDIDATES)
                .accepted()).singleElement()
                .extracting(ConstraintGuardrail.Accepted::needsConfirmation).isEqualTo(false);
    }

    @Test
    void mutualExclusion_SuppressesTheWeakerPrecedenceReading() {
        // mutual exclusion 0.96, precedence 0.32 - accepting the weak one would add an ordering
        // the operator never asked for
        assertThat(typesFor("mutual-exclusion")).containsExactly(ConstraintType.MUTUAL_EXCLUSION);
    }

    @Test
    void synchronizedFinish_SuppressesWeakDeadline() {
        // synchronized finish 0.95, deadline 0.41 - "finish together with" is not a clock time
        assertThat(typesFor("synchronized-finish")).containsExactly(ConstraintType.SYNCHRONIZED_FINISH);
    }

    @Test
    void noConstraint_ProposesNothing() {
        // states_any_constraint 0.04, so the per-type answers are not even read
        assertThat(read("no-constraint")).isEmpty();
    }

    @Test
    void pairConstraint_CarriesTheReferencedJobByName() {
        // refers_to_another_job 0.64, and the choice names render-batch at 0.98
        assertThat(read("precedence")).singleElement()
                .extracting(ClassifiedConstraint::referent).isEqualTo("render-batch");
    }

    @Test
    void referencedJob_ResolvesADescriptionNotJustAName() {
        // "the ETL job" is not any job's name; the choice picks nightly-etl at 1.00
        assertThat(read("dependency")).singleElement()
                .extracting(ClassifiedConstraint::referent).isEqualTo("nightly-etl");
    }

    @Test
    void singleJobConstraint_CarriesNoReferent() {
        // The choice still picks a job - render-batch at 0.39 - but refers_to_another_job is 0.02,
        // and that is the answer that means anything. A referent here would be fabricated.
        assertThat(read("deadline-only")).singleElement()
                .extracting(ClassifiedConstraint::referent).isNull();
    }

    @Test
    void everyProposal_CitesASpanThatIsReallyInTheMessage() {
        // What ConstraintGuardrail checks before anything reaches a job; a proposal citing wording
        // the message does not contain is the shape a made-up constraint would arrive in.
        ConstraintGuardrail guardrail = new ConstraintGuardrail(0.7, 0.3);
        for (String fixture : MESSAGES.keySet()) {
            String message = MESSAGES.get(fixture);
            ConstraintGuardrail.Review review = guardrail.review(message, read(fixture),
                    Set.of(ConstraintType.values()), 999L, CANDIDATES);
            assertThat(review.refused())
                    .describedAs("%s: %s", fixture, message)
                    .noneMatch(r -> r.rejection() == ConstraintGuardrail.Rejection.EVIDENCE_NOT_IN_TEXT);
        }
    }

    @Test
    void probabilityIsCarriedThrough_SoTheGuardrailCanBandIt() {
        // min(ordering 0.87, direction certainty 0.89) - the weaker of the two judgements the
        // reading rests on, so neither can be confident on the other's behalf.
        assertThat(read("precedence")).singleElement()
                .extracting(ClassifiedConstraint::probability)
                .satisfies(p -> assertThat((double) p).isEqualTo(0.87, org.assertj.core.data.Offset.offset(0.001)));
    }

    private List<ConstraintType> typesFor(String fixture) {
        return read(fixture).stream().map(ClassifiedConstraint::type).toList();
    }

    private List<ClassifiedConstraint> read(String fixture) {
        return reader.read(load(fixture), MESSAGES.get(fixture), CANDIDATES);
    }

    /** A recorded response with its direction answer replaced, for a case the API never produced. */
    private static JevAnswers withDirection(String fixture, double thisJobFirst) {
        JevAnswers recorded = load(fixture);
        Map<String, JevAnswers.Answer> answers = new java.util.LinkedHashMap<>(recorded.answers());
        answers.put(ConstraintQuestions.THIS_JOB_FIRST,
                new JevAnswers.Answer("noul", thisJobFirst, null, null, null));
        return new JevAnswers(recorded.model(), answers, recorded.usage());
    }

    private static JevAnswers load(String fixture) {
        String path = "/jev/" + fixture + ".json";
        try (InputStream in = JevAnswerReaderTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing recorded response " + path);
            }
            return new ObjectMapper().readValue(in, JevAnswers.class);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
