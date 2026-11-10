package com.gacs.backend.service.constraintparse;

import com.gacs.backend.service.allocation.ConstraintType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real HTTP path against the live TypeSafe API.
 *
 * <p>Skipped unless {@code TYPESAFE_API_KEY} is in the environment, the same way the Postgres
 * integration tests gate on {@code TEST_POSTGRES_URL}. So CI and anyone without a key never make a
 * network call, and nobody is billed by running the suite.
 *
 * <p>Everything else about this layer is tested against recorded responses, which is the right
 * default - they are fast, offline and deterministic. What they cannot catch is the request being
 * malformed, the auth header being wrong, or the response schema having moved: a recorded fixture
 * proves the reader works on last week's shape. This covers that one gap, and only loosely, because
 * the probabilities are the model's to change.
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
class JevLiveSmokeTest {

    private static final List<JobReferenceResolver.Candidate> CANDIDATES = List.of(
            new JobReferenceResolver.Candidate(100, "alpha-train"),
            new JobReferenceResolver.Candidate(101, "render-batch"),
            new JobReferenceResolver.Candidate(102, "nightly-etl"));

    private JevConstraintClassifier classifier() {
        JevProperties properties = new JevProperties(System.getenv("TYPESAFE_API_KEY"), "jev-1.14.0",
                "https://api.typesafe.ai/v1/systemone", Duration.ofSeconds(10), 0.7, 0.3);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(properties.timeout());
        return new JevConstraintClassifier(RestClient.builder().requestFactory(factory), properties,
                new JevAnswerReader(0.3));
    }

    @Test
    void aPlainDeadlineComesBackAsADeadlineAndNothingElse() {
        List<ClassifiedConstraint> result = classifier()
                .classify("Finish by 6pm tomorrow.", Set.of(ConstraintType.values()));

        assertThat(result).extracting(ClassifiedConstraint::type).containsExactly(ConstraintType.DEADLINE);
    }

    @Test
    void anOrderingComesBackWithTheRightDirectionAndTheRightJob() {
        // The two things that would be silently wrong in production rather than noisily broken:
        // a backwards ordering, and a constraint bound to the wrong job.
        JevConstraintClassifier classifier = classifier();

        assertThat(classifier.classify("This must finish before render-batch starts.",
                Set.of(ConstraintType.values()), CANDIDATES, "nightly-etl"))
                .singleElement().satisfies(proposal -> {
                    assertThat(proposal.type()).isEqualTo(ConstraintType.PRECEDENCE);
                    assertThat(proposal.referent()).isEqualTo("render-batch");
                });

        assertThat(classifier.classify("Run it after the ETL job is done.",
                Set.of(ConstraintType.values()), CANDIDATES, "alpha-train"))
                .singleElement().satisfies(proposal -> {
                    assertThat(proposal.type()).isEqualTo(ConstraintType.DEPENDENCY);
                    assertThat(proposal.referent()).isEqualTo("nightly-etl");
                });
    }

    @Test
    void textAboutNothingSchedulingRelatedProposesNothing() {
        assertThat(classifier().classify("Needs 16 GPUs and 400GB of scratch disk.",
                Set.of(ConstraintType.values()), CANDIDATES, "alpha-train")).isEmpty();
    }

    @Test
    void aBadKeyIsAnErrorAndNeverAnEmptyAnswer() {
        // The fail-closed contract, against the real service rather than a mock: a rejected call
        // must not look like "this message asks for nothing", which is a valid empty result.
        JevProperties bad = new JevProperties("sk-not-a-real-key", "jev-1.14.0",
                "https://api.typesafe.ai/v1/systemone", Duration.ofSeconds(10), 0.7, 0.3);
        JevConstraintClassifier broken = new JevConstraintClassifier(RestClient.builder(), bad,
                new JevAnswerReader(0.3));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> broken.classify("Finish by 6pm tomorrow.", Set.of(ConstraintType.values()))))
                .isNotNull();
    }
}
