package com.gacs.backend.service.constraintparse;

import com.gacs.backend.service.allocation.ConstraintType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one System One response into constraint proposals.
 *
 * <p>Pure, and separate from the HTTP call, so the rules below can be tested against recorded
 * responses without a network or a key.
 *
 * <p>This is where judgements become decisions. Jev answers one question at a time and cannot see
 * its own other answers, so anything that depends on two of them is settled here, in code that can
 * be read and tested.
 */
public final class JevAnswerReader {

    /**
     * Below this, a type is not proposed at all. Above {@code applyAbove} in
     * {@link ConstraintGuardrail} it applies; between, the operator is asked.
     */
    private final double floor;

    public JevAnswerReader(double floor) {
        this.floor = floor;
    }

    /**
     * A daily window's closing hour reads as a deadline, and no wording fixed it.
     *
     * <p>Measured: "only ever run this overnight, between 11pm and 5am" scores 0.72 for deadline
     * and 0.90 for daily window; "restrict it to the 7pm-10pm block every evening" scores 0.63 and
     * 0.94. Genuine deadlines score 0.98-0.99 with the window question near 0.08. Three wordings
     * were tried; the clearest of them left the overlap, and one made it worse. The separation is
     * real and consistent, so it is handled as a rule rather than by pushing on the criteria until
     * a particular sentence behaves - which the model's next version would undo anyway.
     *
     * <p>The rule: when the message clearly states a daily window, a deadline reading weaker than
     * a confident one is treated as that window's end rather than a separate constraint. A deadline
     * strong enough to stand on its own still comes through, because a message can legitimately say
     * both ("overnight only, and done before Monday").
     */
    static final double WINDOW_PRESENT = 0.80;
    static final double DEADLINE_STANDS_ALONE = 0.90;

    /**
     * A confident pair constraint also reads as a weak deadline, for the same reason.
     *
     * <p>Measured: "this must finish before render-batch starts" scores 0.88 for precedence and
     * 0.66 for deadline; "it has to finish together with render-batch" scores 0.95 and 0.41. The
     * deadline question is firing on the words "finish" and "before", and it is not wrong to see
     * them - but the message names no time of day, so a deadline read out of it has no value for
     * the deterministic parser to extract. The relation to the other job is the whole content.
     *
     * <p>Treated the same way as the daily window above: when a pair type is confident, a deadline
     * reading weaker than a confident one is that relation, not a clock deadline. A message that
     * genuinely says both still comes through - "must finish before render-batch, and in any case
     * by 6pm" scores the deadline high enough to stand on its own.
     */
    static final double PAIR_PRESENT = 0.80;

    /**
     * How sure the direction of an ordering has to be before it is applied without asking.
     *
     * <p>Not a separate band: the direction's certainty is folded into the proposal's own
     * probability, so the existing apply/review/drop bands in {@link ConstraintGuardrail} do the
     * work. A direction answer near 0.5 is a coin flip between PRECEDENCE and DEPENDENCY, and
     * capping the proposal at that value drops it into the review band, where the operator is
     * asked - which is the right outcome, because getting the direction wrong inverts their intent
     * and the resulting schedule looks perfectly plausible either way.
     */
    private static double directionCertainty(double thisJobFirst) {
        return Math.max(thisJobFirst, 1.0 - thisJobFirst);
    }

    /**
     * @param text       the operator's message, needed because every proposal must cite a span of it
     * @param candidates the operator's jobs, in the order their ids were offered as Choice options
     */
    public List<ClassifiedConstraint> read(JevAnswers response, String text,
                                           List<JobReferenceResolver.Candidate> candidates) {
        // Nothing here is a constraint: believe the question asked about exactly that, rather than
        // reading six lukewarm type answers as six weak constraints.
        if (response.answer(ConstraintQuestions.ANY_CONSTRAINT).nounProbability() < floor) {
            return List.of();
        }

        Map<String, ConstraintQuestions.TypeQuestion> byId = ConstraintQuestions.byType();
        double windowScore = probabilityOf(response, byId, ConstraintType.ABSOLUTE_TIME_WINDOW);
        double precedenceScore = probabilityOf(response, byId, ConstraintType.PRECEDENCE);
        double exclusionScore = probabilityOf(response, byId, ConstraintType.MUTUAL_EXCLUSION);
        double pairScore = Math.max(precedenceScore,
                Math.max(exclusionScore, probabilityOf(response, byId, ConstraintType.SYNCHRONIZED_FINISH)));

        String referent = referencedJobName(response, candidates);
        Set<String> pairIds = Set.copyOf(ConstraintQuestions.pairQuestionIds());

        double thisJobFirst = response.answer(ConstraintQuestions.THIS_JOB_FIRST).nounProbability();

        List<ClassifiedConstraint> proposals = new ArrayList<>();
        for (Map.Entry<String, ConstraintQuestions.TypeQuestion> entry : byId.entrySet()) {
            double probability = response.answer(entry.getKey()).nounProbability();
            if (probability < floor) {
                continue;
            }
            ConstraintType type = entry.getValue().type();
            // The ordering question detects an ordering in either direction; which direction it is
            // comes from its own question, and its certainty caps the proposal so an unclear
            // direction is confirmed by the operator rather than guessed. Both readings order the
            // same pair, so only one of them is ever proposed.
            if (type == ConstraintType.PRECEDENCE) {
                probability = Math.min(probability, directionCertainty(thisJobFirst));
                if (probability < floor) {
                    continue;
                }
                type = thisJobFirst >= 0.5 ? ConstraintType.PRECEDENCE : ConstraintType.DEPENDENCY;
            }
            if (type == ConstraintType.DEADLINE && probability < DEADLINE_STANDS_ALONE
                    && (windowScore >= WINDOW_PRESENT || pairScore >= PAIR_PRESENT)) {
                continue;
            }
            // Precedence implies mutual exclusion: a job that must finish before another starts
            // cannot overlap it. So the two together never restrict more than the stronger reading
            // alone, and proposing both would ask the operator to confirm one fact twice. Keep the
            // stronger. This is not calibration - it holds whatever the probabilities are - but it
            // does fire on real answers: the three pair questions bleed into each other, scoring
            // 0.34 for exclusion on a 0.88 precedence message and 0.32 for precedence on a 0.96
            // exclusion one.
            if (type == ConstraintType.MUTUAL_EXCLUSION && precedenceScore > probability) {
                continue;
            }
            if ((type == ConstraintType.PRECEDENCE || type == ConstraintType.DEPENDENCY)
                    && exclusionScore > probability) {
                continue;
            }
            // A pair constraint needs a second job. With none named there is nothing to bind to,
            // so this is not a weak reading of something - it is unreadable, whatever the type
            // answer says. Measured: "overnight only, and done before Monday" scores 0.31 for
            // precedence while scoring 0.04 for mentioning a second job. ConstraintGuardrail would
            // refuse it too, and still will for any other classifier, but a proposal that is
            // certain to be refused should not be made: the operator would see a rejected
            // precedence constraint on a message about nothing but hours.
            if (pairIds.contains(entry.getKey()) && referent == null) {
                continue;
            }
            // The whole message is cited: these questions judge the message, not a span of it, and
            // the guardrail requires a span that is really in the text. Claiming a narrower one
            // would be inventing precision the answer does not carry.
            proposals.add(new ClassifiedConstraint(type, probability, text, 0, text.length(),
                    pairIds.contains(entry.getKey()) ? referent : null));
        }
        return List.copyOf(proposals);
    }

    private static double probabilityOf(JevAnswers response,
                                        Map<String, ConstraintQuestions.TypeQuestion> byId,
                                        ConstraintType wanted) {
        return byId.entrySet().stream()
                .filter(e -> e.getValue().type() == wanted)
                .mapToDouble(e -> response.answer(e.getKey()).nounProbability())
                .max().orElse(0.0);
    }

    /**
     * The job the message points at, or null.
     *
     * <p>Gated on its own Noul, not on the Choice. A Choice always picks something: asked which job
     * "needs 16 GPUs and 400GB of scratch disk" refers to, the live model answered with a job at
     * 0.85. The companion question scored that same message 0.06 for referring to any job at all,
     * which is the answer that means anything.
     */
    private String referencedJobName(JevAnswers response, List<JobReferenceResolver.Candidate> candidates) {
        if (response.answer(ConstraintQuestions.REFERS_TO_ANOTHER_JOB).nounProbability() < floor) {
            return null;
        }
        JevAnswers.Answer choice = response.answer(ConstraintQuestions.REFERENCED_JOB);
        if (choice.choice() == null || choice.chosenProbability() < floor) {
            return null;
        }
        // Options are keyed by job id, so the pick is resolved back to a name the guardrail and the
        // operator can both read; the resolver maps it to an id again, reporting any ambiguity.
        return candidates.stream()
                .filter(candidate -> String.valueOf(candidate.id()).equals(choice.choice()))
                .map(JobReferenceResolver.Candidate::name)
                .findFirst().orElse(null);
    }
}
