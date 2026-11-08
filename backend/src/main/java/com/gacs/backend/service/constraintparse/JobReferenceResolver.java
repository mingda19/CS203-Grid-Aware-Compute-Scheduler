package com.gacs.backend.service.constraintparse;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns the words an operator used for another job ("must finish before B") into that job's id.
 *
 * <p>Deliberately deterministic, and deliberately not a lookup that can quietly pick one. Job names
 * are not unique in this system - nothing enforces it, and this database currently holds two jobs
 * called {@code gpt-agent-5} owned by the same operator - so a name does not identify a job. A
 * resolver that returned "the first match" would attach a precedence constraint to whichever row
 * happened to come back first, and the operator would have no way to tell.
 *
 * <p>So there are three outcomes, and the caller must handle all three. The constraint is stored
 * against the resolved <em>id</em>, never the name: a job renamed afterwards must not silently
 * detach its own constraints.
 */
public final class JobReferenceResolver {

    private JobReferenceResolver() {
    }

    /** The jobs a reference may resolve against: the operator's own, nothing else. */
    public record Candidate(long id, String name) {
        public Candidate {
            Objects.requireNonNull(name, "name");
        }
    }

    public sealed interface Resolution {

        /** Exactly one job matched. */
        record Resolved(long jobId, String name) implements Resolution { }

        /**
         * No job matched. Not deferred: a constraint pointing at a job that does not exist yet
         * cannot be checked, cannot be solved against, and would have to carry its own validity
         * lifecycle. The operator is told to create that job first.
         */
        record NotFound(String referent) implements Resolution { }

        /** Several jobs share the name, so the operator has to say which. */
        record Ambiguous(String referent, List<Long> jobIds) implements Resolution {
            public Ambiguous {
                jobIds = List.copyOf(jobIds);
            }
        }
    }

    /**
     * Resolves {@code referent} against {@code candidates}, matching on name, case-insensitively
     * and ignoring surrounding whitespace.
     *
     * <p>Exact match only. No fuzzy matching, no prefix matching, no "closest" match: this decides
     * which job a hard scheduling constraint binds to, and a near-miss that silently picks a
     * neighbour is worse than a rejection the operator can see and correct.
     */
    public static Resolution resolve(String referent, List<Candidate> candidates) {
        if (referent == null || referent.isBlank()) {
            return new Resolution.NotFound(referent == null ? "" : referent);
        }
        String wanted = normalise(referent);
        List<Long> matches = candidates.stream()
                .filter(candidate -> normalise(candidate.name()).equals(wanted))
                .map(Candidate::id)
                .toList();
        return switch (matches.size()) {
            case 0 -> new Resolution.NotFound(referent.strip());
            case 1 -> new Resolution.Resolved(matches.getFirst(), referent.strip());
            default -> new Resolution.Ambiguous(referent.strip(), matches);
        };
    }

    private static String normalise(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
