package com.gacs.backend.service.constraintparse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.gacs.backend.service.constraintparse.JobReferenceResolver.Candidate;
import static com.gacs.backend.service.constraintparse.JobReferenceResolver.Resolution;
import static com.gacs.backend.service.constraintparse.JobReferenceResolver.resolve;
import static org.assertj.core.api.Assertions.assertThat;

/** Resolving "the other job" in a constraint like "A must finish before B". */
class JobReferenceResolverTest {

    private static final List<Candidate> JOBS = List.of(
            new Candidate(1L, "nightly-etl"),
            new Candidate(2L, "GPT-agent-7"),
            new Candidate(3L, "render-batch"));

    @Test
    @DisplayName("a name matching exactly one job resolves to that job's id")
    void exactMatchResolves() {
        assertThat(resolve("render-batch", JOBS)).isEqualTo(new Resolution.Resolved(3L, "render-batch"));
    }

    @Test
    @DisplayName("matching ignores case and surrounding whitespace, since operators type prose")
    void matchingIsForgivingOfFormatting() {
        assertThat(resolve("  gpt-AGENT-7 ", JOBS)).isEqualTo(new Resolution.Resolved(2L, "gpt-AGENT-7"));
    }

    @Test
    @DisplayName("duplicate names are ambiguous, never silently first-wins")
    void duplicateNamesAreAmbiguous() {
        // This database really does hold two jobs called gpt-agent-5 for one owner. Picking either
        // would bind a hard scheduling constraint to an arbitrary one of them, invisibly.
        List<Candidate> withDuplicates = List.of(
                new Candidate(7L, "gpt-agent-5"), new Candidate(9L, "gpt-agent-5"));

        assertThat(resolve("gpt-agent-5", withDuplicates))
                .isEqualTo(new Resolution.Ambiguous("gpt-agent-5", List.of(7L, 9L)));
    }

    @Test
    @DisplayName("a job that does not exist is not found, rather than deferred or invented")
    void unknownNameIsNotFound() {
        assertThat(resolve("does-not-exist", JOBS))
                .isEqualTo(new Resolution.NotFound("does-not-exist"));
    }

    @Test
    @DisplayName("a near miss is not found: no fuzzy or prefix matching")
    void nearMissesDoNotResolve() {
        // "render" is a prefix of "render-batch" and a typo away from it. Binding a constraint to a
        // neighbouring job is worse than a rejection the operator can see.
        assertThat(resolve("render", JOBS)).isInstanceOf(Resolution.NotFound.class);
        assertThat(resolve("render-batchh", JOBS)).isInstanceOf(Resolution.NotFound.class);
        assertThat(resolve("nightly etl", JOBS)).isInstanceOf(Resolution.NotFound.class);
    }

    @Test
    @DisplayName("an empty or absent referent is not found rather than throwing")
    void emptyReferentIsNotFound() {
        assertThat(resolve(null, JOBS)).isInstanceOf(Resolution.NotFound.class);
        assertThat(resolve("   ", JOBS)).isInstanceOf(Resolution.NotFound.class);
        assertThat(resolve("anything", List.of())).isInstanceOf(Resolution.NotFound.class);
    }
}
