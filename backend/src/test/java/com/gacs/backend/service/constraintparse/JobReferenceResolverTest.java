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

    // ---------------------------------------------------------------- normalisation

    @Test
    @DisplayName("the same name in two Unicode encodings matches itself")
    void composedAndDecomposedFormsMatch() {
        // "café" with a precomposed é, versus "cafe" + a combining acute. Identical on screen, and
        // an operator has no way to tell which one their job was created with.
        List<Candidate> precomposed = List.of(new Candidate(4L, "caf\u00e9-batch"));
        assertThat(resolve("cafe\u0301-batch", precomposed))
                .isInstanceOf(Resolution.Resolved.class);

        List<Candidate> decomposed = List.of(new Candidate(5L, "cafe\u0301-batch"));
        assertThat(resolve("caf\u00e9-batch", decomposed))
                .isInstanceOf(Resolution.Resolved.class);
    }

    @Test
    @DisplayName("invisible characters pasted into a name do not stop it matching")
    void zeroWidthCharactersAreIgnored() {
        // A name copied out of a document can carry a zero-width space or joiner. Nobody can type
        // one deliberately, so treating it as part of the name only produces a baffling NotFound.
        List<Candidate> withZeroWidth = List.of(new Candidate(6L, "nightly\u200b-etl"));
        assertThat(resolve("nightly-etl", withZeroWidth)).isInstanceOf(Resolution.Resolved.class);
        assertThat(resolve("nightly\u200d-etl", JOBS)).isInstanceOf(Resolution.Resolved.class);
    }

    @Test
    @DisplayName("names that collide only after normalising are ambiguous, not silently merged")
    void collisionAfterNormalisingIsAmbiguous() {
        // Normalising makes more things equal, so it can create ambiguity that was not there
        // before. That is the safe direction: the operator is asked rather than guessed at.
        List<Candidate> colliding = List.of(
                new Candidate(1L, "caf\u00e9"), new Candidate(2L, "cafe\u0301"));

        assertThat(resolve("caf\u00e9", colliding))
                .isEqualTo(new Resolution.Ambiguous("caf\u00e9", List.of(1L, 2L)));
    }

    @Test
    @DisplayName("a legitimate non-ASCII name still resolves rather than being rejected")
    void nonAsciiNamesAreUsable() {
        // Normalising, not rejecting: a name is not suspicious for being written in another script.
        List<Candidate> jobs = List.of(
                new Candidate(8L, "夜間バッチ"), new Candidate(9L, "ETL-\u00dcberblick"));

        assertThat(resolve("夜間バッチ", jobs)).isEqualTo(new Resolution.Resolved(8L, "夜間バッチ"));
        assertThat(resolve("etl-\u00fcberblick", jobs)).isInstanceOf(Resolution.Resolved.class);
    }
}
