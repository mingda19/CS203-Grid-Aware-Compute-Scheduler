package com.gacs.backend.service.constraintparse;

import com.gacs.backend.service.allocation.ConstraintType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every question the classifier is asked, and every word of their wording, in one place.
 *
 * <p>Questions, option names and thresholds are the parts of an integration that get audited and
 * edited most, so they live together rather than being scattered through the calling code.
 *
 * <p>Shape, and why:
 * <ul>
 *   <li><b>One Noul per constraint type, not one Choice over the types.</b> A Choice picks exactly
 *       one option, but "by 6pm, can wait 4 hours, and must finish before the ETL job" is three
 *       constraints in one sentence - a Choice would split probability across them and return one.
 *       Separate Nouls let each be true on its own.</li>
 *   <li><b>A companion Noul for the null case.</b> Asked because "nothing here is a constraint" is
 *       a real and common answer that needs to be distinguishable from "every type came back
 *       lukewarm".</li>
 *   <li><b>The referent is a Choice over the operator's own jobs, not a name to extract.</b> Code
 *       supplies the candidates and the model picks which one is meant, so "the nightly one"
 *       resolves where matching a typed-out name never would. Options are keyed by job id because
 *       job names are not unique here; the readable name travels in the state.</li>
 *   <li><b>Asked speculatively.</b> The referent question goes in the same request as the questions
 *       that decide whether it matters, since questions are cheap, run in parallel, and the state
 *       is paid for once. Code reads the answer only when a pair constraint actually came back.</li>
 * </ul>
 *
 * <p>Values are not asked for. The model says which <em>kinds</em> of statement are present; the
 * deterministic readers already in this package pull out the times and numbers, because they
 * handle time zones, DST and relative phrases correctly and have no reason to be replaced by
 * something probabilistic.
 */
public final class ConstraintQuestions {

    private ConstraintQuestions() {
    }

    /** Question id for "does this text state any scheduling constraint at all?". */
    public static final String ANY_CONSTRAINT = "states_any_constraint";

    /** Question id for "which of the operator's jobs does this text refer to?". */
    public static final String REFERENCED_JOB = "referenced_job";

    /** Question id for "does this text refer to another job at all?". */
    public static final String REFERS_TO_ANOTHER_JOB = "refers_to_another_job";

    /**
     * One Noul per type, keyed by the question id used in the request and answer maps.
     *
     * <p>Each asks for one judgement a person could make at a glance, phrased so that a true answer
     * means yes, and naming the case that is easy to confuse it with. The distinction that needs
     * saying most is deadline versus flexibility window: "finish by 6pm" and "can wait 4 hours" are
     * both about timing, and both are about when the job may run, but they constrain different
     * ends of it.
     */
    public static Map<String, TypeQuestion> byType() {
        Map<String, TypeQuestion> questions = new LinkedHashMap<>();
        questions.put("states_deadline", new TypeQuestion(ConstraintType.DEADLINE,
                "Does the message give a time this job must be finished by?",
                "The message names a time or date the work has to be complete by, such as \"by 6pm\" "
                        + "or \"before Friday morning\".",
                "The message says nothing about when the work must be complete. A delay the job can "
                        + "tolerate, such as \"can wait 4 hours\", is not a finish-by time."));
        questions.put("states_flexibility", new TypeQuestion(ConstraintType.FLEXIBILITY_WINDOW,
                "Does the message say how long this job may be delayed past its planned start?",
                "The message gives an amount of delay that is acceptable, such as \"can wait 4 hours\" "
                        + "or \"start any time this morning\".",
                "The message says nothing about delaying the start. A time the job must finish by is "
                        + "not a delay allowance."));
        questions.put("states_daily_window", new TypeQuestion(ConstraintType.ABSOLUTE_TIME_WINDOW,
                "Does the message restrict this job to a time of day that repeats every day?",
                "The message names hours of the day the job is allowed to run in, meant to apply on "
                        + "any day, such as \"only overnight\" or \"between 7pm and 10pm\".",
                "The message names no recurring hours. A one-off time on a specific date is not a "
                        + "recurring daily window."));
        questions.put("states_precedence", new TypeQuestion(ConstraintType.PRECEDENCE,
                "Does the message say this job must finish before another job starts?",
                "The message puts this job ahead of another one in order, such as \"must finish "
                        + "before the ETL job\" or \"run this first, then the report\".",
                "The message names no ordering against another job. Two jobs merely being mentioned "
                        + "together is not an ordering."));
        questions.put("states_mutual_exclusion", new TypeQuestion(ConstraintType.MUTUAL_EXCLUSION,
                "Does the message say this job must never run at the same time as another job?",
                "The message says the two jobs cannot overlap, such as \"never at the same time as "
                        + "the backup\" or \"these two must not clash\".",
                "The message does not forbid overlap. Saying one job comes before another is an "
                        + "ordering, not a ban on overlapping."));
        questions.put("states_synchronized_finish", new TypeQuestion(ConstraintType.SYNCHRONIZED_FINISH,
                "Does the message say this job must finish at the same time as another job?",
                "The message requires the jobs to complete together, such as \"must finish with the "
                        + "nightly batch\".",
                "The message does not require them to finish together. Running at the same time is "
                        + "not the same as finishing at the same time."));
        return questions;
    }

    /** The wording of one type's Noul. */
    public record TypeQuestion(ConstraintType type, String instructions, String yes, String no) { }

    /** "Does this text state any scheduling constraint at all?" - the null case, asked separately. */
    public static TypeQuestion anyConstraint() {
        return new TypeQuestion(null,
                "Does the message state any rule about when this job may run?",
                "The message gives at least one timing or ordering rule for the job.",
                "The message describes the job, asks a question, or says something unrelated to when "
                        + "it may run, such as \"needs 16 GPUs\".");
    }

    /** "Does this text refer to another job at all?" - the null case for the referent Choice. */
    public static TypeQuestion refersToAnotherJob() {
        return new TypeQuestion(null,
                "Does the message refer to one of the operator's other jobs?",
                "The message points at another job, by name or by description, such as \"the nightly "
                        + "one\" or \"render-batch\".",
                "The message mentions no other job.");
    }

    /**
     * The referent Choice's instructions. Options are supplied by the caller, one per candidate
     * job, keyed by id with the job's name as its description - names repeat in this system, so an
     * id is what a constraint can safely be stored against.
     */
    public static String referencedJobInstructions() {
        return "Which of the operator's jobs does the message refer to?";
    }

    /** Question ids for the pair types, whose answers decide whether the referent matters. */
    public static List<String> pairQuestionIds() {
        return List.of("states_precedence", "states_mutual_exclusion", "states_synchronized_finish");
    }
}
