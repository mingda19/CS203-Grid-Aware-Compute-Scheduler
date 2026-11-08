package com.gacs.backend.service.constraintparse;

import com.gacs.backend.service.allocation.ConstraintType;

import java.util.List;
import java.util.Set;

/**
 * Decides which constraint types a piece of operator text is asking for.
 *
 * <p>Classification only: it picks from {@code allowed} and never invents a type, never returns a
 * value, and never writes prose. Pulling the actual numbers and times out of the text stays with
 * {@link ConstraintParser}'s deterministic readers, which already handle time zones, DST and
 * relative phrases correctly and have no reason to be replaced by a probabilistic component.
 *
 * <p>The implementation is expected to be a classifier model. Nothing it returns is trusted:
 * {@link ConstraintGuardrail} re-checks every field against the operator's own text before any of
 * it reaches a job. A classifier that cannot emit free text cannot emit an unknown type either,
 * which removes a whole class of failure, but it can still be wrong about which of the known types
 * applies, and prompt text inside a constraint could try to push it - so the guardrail treats the
 * result as a proposal, not an answer.
 */
public interface ConstraintClassifier {

    /**
     * @param text    the operator's constraint text, exactly as typed
     * @param allowed the only types that may be returned; anything else is a bug in the adapter
     * @return one entry per constraint the text appears to ask for, in no particular order. Empty
     *         when the text asks for nothing recognisable - which is a valid answer, not an error.
     */
    List<ClassifiedConstraint> classify(String text, Set<ConstraintType> allowed);
}
