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
                "The message names a time or date the work has to be complete by.",
                "The message says nothing about when the work must be complete. A delay the job can "
                        + "tolerate is not a finish-by time."));
        questions.put("states_flexibility", new TypeQuestion(ConstraintType.FLEXIBILITY_WINDOW,
                "Does the message say how long this job may be delayed past its planned start?",
                "The message gives an amount of delay that is acceptable, such as \"can wait 4 hours\" "
                        + "or \"start any time this morning\".",
                "The message says nothing about delaying the start. A time the job must finish by is "
                        + "not a delay allowance."));
        questions.put("states_daily_window", new TypeQuestion(ConstraintType.ABSOLUTE_TIME_WINDOW,
                "Does the message restrict this job to the same hours on every day?",
                "The message names hours of the day that apply every day the job might run, such as "
                        + "\"only ever overnight\" or \"always between 7pm and 10pm\".",
                "The message names no repeating hours. A time on one particular day is not a daily "
                        + "window: \"by 6pm tomorrow\", \"finish before Monday morning\" and \"run it "
                        + "overnight tonight\" all name a single occasion, not a rule for every day."));
        questions.put("states_precedence", new TypeQuestion(ConstraintType.PRECEDENCE,
                "Does the message put this job and another job in order, one after the other?",
                "The message says one of the two jobs has to finish before the other starts, in "
                        + "either direction - \"must finish before the ETL job\", \"run this first, "
                        + "then the report\", \"run it after the backup is done\".",
                "The message names no ordering against another job. Two jobs merely being mentioned "
                        + "together is not an ordering, and jobs being forbidden to overlap is not "
                        + "an ordering either."));
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
                "Does the message state any rule about when this job runs or when it must be done?",
                "The message gives at least one rule about timing or ordering: when it may start, "
                        + "how long it may be delayed, when it must be finished, which hours it may "
                        + "use, or how it is ordered against another job.",
                "The message describes the job, asks a question, or says something unrelated to when "
                        + "it may run, such as \"needs 16 GPUs\".");
    }

    /**
     * "Does this text refer to another job at all?" - the null case for the referent Choice.
     *
     * <p>Phrased to be answerable from the message alone, with no list of the operator's jobs in
     * the state. That is not a stylistic choice: the list has to stay out of the state because it
     * wrecks the Choice below, so this question cannot rely on it either. Asking after "one of the
     * operator's other jobs" without saying what they are scored 0.25 on "this must finish before
     * render-batch starts" - under the floor, discarding a referent the Choice had identified
     * correctly at 0.99. Asking after "a second job" instead scores 0.60-0.96 on the five
     * referring messages measured and 0.02-0.03 on the four that refer to nothing.
     */
    public static TypeQuestion refersToAnotherJob() {
        return new TypeQuestion(null,
                "Does the message mention a second job, separate from the one being described?",
                "The message names or points at another job, such as \"render-batch\", \"the ETL job\" "
                        + "or \"the nightly one\". Job names are often lowercase words joined by hyphens.",
                "The message talks only about this one job and mentions no other. Hardware, data or "
                        + "locations are not jobs.");
    }

    /**
     * The referent Choice's instructions. Options are supplied by the caller, one per candidate
     * job, keyed by id with {@link #referencedJobOption} as its description - names repeat in this
     * system, so an id is what a constraint can safely be stored against.
     *
     * <p>"Choose the job the message points at, not the job the message is about" earns its place:
     * every message here describes one job while naming another, and without that line the answer
     * drifts to the job being described.
     */
    public static String referencedJobInstructions() {
        return "The message mentions another job by name or description. Which job is it? Choose the "
                + "job the message itself points at, not the job the message is about.";
    }

    /**
     * One referent option's description. A sentence rather than the bare name, so the option reads
     * as a claim about the message that can be true or false.
     *
     * <p>The caller must not also put the candidate list in the request state. Doing so anchors the
     * answer to the state's first entry instead of the message: asked which job "this must finish
     * before render-batch starts" refers to, with the three candidates listed in the state, the
     * live model answered alpha-train at 0.95 - and reversing the order of that state list moved
     * the answer to nightly-etl at 0.97, while the message never changed. The same question with
     * no list in the state answers render-batch at 0.98. The options are the list; sending it
     * twice gives the model a second place to read it from, and it reads the wrong one.
     */
    public static String referencedJobOption(String jobName) {
        return "The message refers to the job named \"" + jobName + "\".";
    }

    /** Question ids for the pair types, whose answers decide whether the referent matters. */
    public static List<String> pairQuestionIds() {
        return List.of("states_precedence", "states_mutual_exclusion", "states_synchronized_finish");
    }

    /** Which way round an ordering goes. See {@link #thisJobFirst()}. */
    public static final String THIS_JOB_FIRST = "this_job_first";

    /**
     * "Of the two jobs being ordered, does this one go first?" - the direction of an ordering,
     * which decides {@link ConstraintType#PRECEDENCE} against {@link ConstraintType#DEPENDENCY}.
     *
     * <p>Asked as one question with the two directions as its yes and no, and <em>not</em> as two
     * separate type questions. That was measured, not assumed. Two independent Nouls - "must this
     * finish before another?" and "must this wait for another?" - both fire on the same message:
     * "run it after the ETL job is done" scored 0.88 for the first and 0.95 for the second, and
     * "run this first, then render-batch" scored 0.90 and 0.79. A Noul judges one proposition on
     * its own, and both propositions rest on the same evidence - that an ordering exists - so
     * neither can rule the other out. Direction is a choice between mutually exclusive readings,
     * so it has to be asked as one.
     *
     * <p>Asked that way it separates cleanly: 0.89-0.96 for this-job-first against 0.03-0.05 for
     * the other-job-first across six measured messages.
     */
    public static TypeQuestion thisJobFirst() {
        return new TypeQuestion(null,
                "Does this job go first, before the other job the message mentions?",
                "This job goes first: it finishes, then the other one starts. \"Must finish before "
                        + "render-batch\", \"run this first, then the report\".",
                "The other job goes first: it finishes, then this one starts. \"Run it after the ETL "
                        + "job is done\", \"only once the backup has finished\".");
    }
}
