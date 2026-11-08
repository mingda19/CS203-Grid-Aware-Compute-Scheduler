package com.gacs.backend.service.constraintparse;

import com.gacs.backend.service.allocation.ConstraintType;

import java.util.Objects;

/**
 * One constraint a {@link ConstraintClassifier} believes the text asks for.
 *
 * @param type       which constraint, from the closed set the classifier was given
 * @param probability the likelihood this reading is correct, 0-1, and <em>not</em> the model's
 *                   {@code confidence} field. Confidence measures how concentrated a distribution
 *                   is, which is a different quantity: a two-way split between two readings that
 *                   are both plausible is low-confidence but may still have a high probability on
 *                   the top one, and a sharply concentrated answer can be sharply wrong. A Noul
 *                   does not report confidence at all - it returns one probability - so gating on
 *                   confidence would not even be expressible for the questions this uses.
 * @param phrase     the span of the operator's text this came from, copied verbatim
 * @param start      index of {@code phrase} in the text, inclusive
 * @param end        index of {@code phrase} in the text, exclusive
 * @param referent   for a constraint about two jobs, the words naming the other one ("B" in "must
 *                   finish before B"); null for a single-job constraint. A name, never an id: the
 *                   classifier sees text, so resolving it to a job is {@link JobReferenceResolver}'s
 *                   job and can fail in ways the classifier cannot know about.
 *
 * <p>Direction lives in {@link #type}, not in a separate field: an ordering is
 * {@link ConstraintType#PRECEDENCE} when this job goes first and
 * {@link ConstraintType#DEPENDENCY} when the other one does. That keeps the pair always "this job
 * and the referent", so a reader of one of these never has to work out which end is which - and it
 * means direction gets the probability banding every other judgement gets, rather than riding
 * along unchecked on a boolean. {@link JevAnswerReader} folds the direction's own certainty into
 * {@link #probability}, so an unclear direction is confirmed by the operator instead of guessed.
 */
public record ClassifiedConstraint(
        ConstraintType type,
        double probability,
        String phrase,
        int start,
        int end,
        String referent) {

    public ClassifiedConstraint {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(phrase, "phrase");
    }

    /** A single-job classification, with no other job referred to. */
    public static ClassifiedConstraint of(ConstraintType type, double probability, String phrase, int start, int end) {
        return new ClassifiedConstraint(type, probability, phrase, start, end, null);
    }

    public boolean namesAnotherJob() {
        return referent != null && !referent.isBlank();
    }
}
