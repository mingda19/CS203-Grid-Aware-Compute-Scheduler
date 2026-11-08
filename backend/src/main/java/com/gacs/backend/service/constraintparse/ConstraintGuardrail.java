package com.gacs.backend.service.constraintparse;

import com.gacs.backend.service.allocation.ConstraintType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The deterministic layer between a {@link ConstraintClassifier} and anything that touches a job.
 *
 * <p>Its guarantee is the one a probabilistic component cannot give: nothing reaches a job unless
 * it survives checks that do not involve a model. A second model asked to review the first would
 * only add another thing that can be wrong, correlated with the first and equally unbounded - so
 * every check here is ordinary code with a yes or no answer.
 *
 * <p>Four checks, cheapest and most decisive first:
 * <ol>
 *   <li><b>Closed vocabulary.</b> A type outside the set offered is rejected. Mostly structural -
 *       {@link ConstraintType} is an enum, so an unknown type is unrepresentable - but an adapter
 *       could still return a type the <em>caller</em> did not offer for this job.</li>
 *   <li><b>Confidence.</b> Below {@link #minimumConfidence} the proposal is dropped rather than
 *       guessed at. Only meaningful if the score is calibrated; see {@link ClassifiedConstraint}.</li>
 *   <li><b>Evidence binding.</b> The cited span must exist in the operator's text and must read
 *       exactly as the classifier said it does. This is the check that makes a constraint
 *       impossible to invent: one with no source text in the message did not come from the
 *       operator, whatever produced it and for whatever reason.</li>
 *   <li><b>Reference resolution.</b> A constraint naming another job must resolve to exactly one
 *       of the operator's own jobs, and never to the job being edited.</li>
 * </ol>
 *
 * <p>Note what the third check does to prompt injection. Text inside a constraint that tries to
 * steer the classifier can at worst change which known type comes back; it cannot produce a
 * constraint citing words the operator never wrote, because the span is checked against the very
 * text the instruction would have to live in. (The injection risk here is that, not SQL: every
 * query in this codebase already goes through JPA with bound parameters, and no part of this path
 * builds SQL from text.)
 */
public final class ConstraintGuardrail {

    /** Constraints that bind two jobs, so they need a resolvable counterpart. */
    private static final Set<ConstraintType> PAIR_TYPES =
            EnumSet.of(ConstraintType.PRECEDENCE, ConstraintType.MUTUAL_EXCLUSION);

    private final double applyAbove;
    private final double reviewAbove;

    /**
     * @param applyAbove  at or above this probability a proposal is applied without asking
     * @param reviewAbove at or above this, but below {@code applyAbove}, the operator is asked to
     *                    confirm it; below it the proposal is dropped. The band exists because the
     *                    middle of the range is exactly where a parse is worth showing someone
     *                    rather than guessing at - and the review screen already exists.
     */
    public ConstraintGuardrail(double applyAbove, double reviewAbove) {
        this.applyAbove = applyAbove;
        this.reviewAbove = reviewAbove;
    }

    /** Why a proposal was refused, for showing the operator rather than silently dropping it. */
    public enum Rejection {
        NOT_OFFERED,
        TOO_UNCERTAIN,
        EVIDENCE_NOT_IN_TEXT,
        REFERENCE_MISSING,
        REFERENCE_NOT_FOUND,
        REFERENCE_AMBIGUOUS,
        SELF_REFERENCE
    }

    /**
     * A proposal that survived every check.
     *
     * @param needsConfirmation true when its probability fell in the review band, so the operator
     *                          confirms it before it is applied. Everything else about it passed.
     */
    public record Accepted(ClassifiedConstraint proposal, Long referencedJobId, boolean needsConfirmation) { }

    public record Refused(ClassifiedConstraint proposal, Rejection rejection, String message) { }

    public record Review(List<Accepted> accepted, List<Refused> refused) {
        public Review {
            accepted = List.copyOf(accepted);
            refused = List.copyOf(refused);
        }
    }

    /**
     * @param text       the operator's text, exactly as they typed it and as it was classified
     * @param offered    the types the caller is willing to accept for this job
     * @param editingJob the job being edited, so a constraint cannot point at itself; null when
     *                   the job does not exist yet
     * @param candidates the operator's own jobs, the only things a reference may resolve to
     */
    public Review review(String text, List<ClassifiedConstraint> proposals, Set<ConstraintType> offered,
                         Long editingJob, List<JobReferenceResolver.Candidate> candidates) {
        List<Accepted> accepted = new ArrayList<>();
        List<Refused> refused = new ArrayList<>();
        String source = text == null ? "" : text;

        for (ClassifiedConstraint proposal : proposals) {
            if (!offered.contains(proposal.type())) {
                refused.add(new Refused(proposal, Rejection.NOT_OFFERED,
                        proposal.type() + " was not offered for this job"));
                continue;
            }
            if (proposal.probability() < reviewAbove) {
                refused.add(new Refused(proposal, Rejection.TOO_UNCERTAIN, String.format(
                        "Too uncertain to use (%.0f%%, needs at least %.0f%%)",
                        proposal.probability() * 100, reviewAbove * 100)));
                continue;
            }
            boolean confirm = proposal.probability() < applyAbove;
            if (!citesRealText(source, proposal)) {
                refused.add(new Refused(proposal, Rejection.EVIDENCE_NOT_IN_TEXT,
                        "Refers to wording that is not in the message"));
                continue;
            }
            if (!PAIR_TYPES.contains(proposal.type())) {
                accepted.add(new Accepted(proposal, null, confirm));
                continue;
            }
            if (!proposal.namesAnotherJob()) {
                refused.add(new Refused(proposal, Rejection.REFERENCE_MISSING,
                        proposal.type() + " needs another job, and none was named"));
                continue;
            }
            switch (JobReferenceResolver.resolve(proposal.referent(), candidates)) {
                case JobReferenceResolver.Resolution.Resolved r -> {
                    if (editingJob != null && editingJob == r.jobId()) {
                        refused.add(new Refused(proposal, Rejection.SELF_REFERENCE,
                                "A job cannot be constrained against itself"));
                    } else {
                        accepted.add(new Accepted(proposal, r.jobId(), confirm));
                    }
                }
                case JobReferenceResolver.Resolution.NotFound n -> refused.add(new Refused(proposal,
                        Rejection.REFERENCE_NOT_FOUND,
                        "No job of yours is called \"" + n.referent() + "\". Create it first, then add this."));
                case JobReferenceResolver.Resolution.Ambiguous a -> refused.add(new Refused(proposal,
                        Rejection.REFERENCE_AMBIGUOUS,
                        a.jobIds().size() + " of your jobs are called \"" + a.referent()
                                + "\". Rename them or pick one."));
            }
        }
        return new Review(accepted, refused);
    }

    /** The cited span must be inside the text and read exactly as the classifier reported it. */
    private static boolean citesRealText(String text, ClassifiedConstraint proposal) {
        int start = proposal.start();
        int end = proposal.end();
        if (start < 0 || end > text.length() || start >= end) {
            return false;
        }
        return text.substring(start, end).equals(proposal.phrase());
    }
}
