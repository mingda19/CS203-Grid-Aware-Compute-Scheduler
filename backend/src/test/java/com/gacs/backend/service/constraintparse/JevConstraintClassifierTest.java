package com.gacs.backend.service.constraintparse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.service.allocation.ConstraintType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The HTTP adapter: what it sends, what it does with what comes back, and what it does when
 * nothing comes back. No network and no key.
 */
class JevConstraintClassifierTest {

    private static final String ENDPOINT = "https://api.typesafe.ai/v1/systemone";
    private static final Set<ConstraintType> ALL = Set.of(ConstraintType.values());
    private static final List<JobReferenceResolver.Candidate> CANDIDATES = List.of(
            new JobReferenceResolver.Candidate(100, "alpha-train"),
            new JobReferenceResolver.Candidate(101, "render-batch"));

    private final ObjectMapper json = new ObjectMapper();
    private final JevProperties properties =
            new JevProperties("test-key", "jev-1.14.0", ENDPOINT, Duration.ofSeconds(5), 0.7, 0.3);

    private MockRestServiceServer server;
    private JevConstraintClassifier classifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        classifier = new JevConstraintClassifier(builder, properties, new JevAnswerReader(0.3));
    }

    @Test
    void sendsThePinnedModelAndBearerKeyToTheEndpoint() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(body("""
                        {"states_deadline":{"type":"noul","noul":0.99},
                         "states_any_constraint":{"type":"noul","noul":0.95}}"""), MediaType.APPLICATION_JSON));

        classifier.classify("Finish by 6pm tomorrow.", ALL);

        server.verify();
    }

    @Test
    void asksOneQuestionPerAllowedTypePlusTheNullCase() {
        JsonNode sent = sentBody("Finish by 6pm.", ALL, List.of(), null);

        assertThat(sent.get("model").asText()).isEqualTo("jev-1.14.0");
        assertThat(sent.get("questions").properties()).extracting(e -> e.getKey())
                .containsExactlyInAnyOrder("states_deadline", "states_flexibility", "states_daily_window",
                        "states_precedence", "states_mutual_exclusion", "states_synchronized_finish",
                        "states_any_constraint");
    }

    @Test
    void asksOnlyAboutTheTypesOffered() {
        // Billing is per question, and a type that cannot be applied should not be paid for.
        JsonNode sent = sentBody("Finish by 6pm.", Set.of(ConstraintType.DEADLINE), List.of(), null);

        assertThat(sent.get("questions").properties()).extracting(e -> e.getKey())
                .containsExactlyInAnyOrder("states_deadline", "states_any_constraint");
    }

    @Test
    void asksTheDirectionQuestionWhenAnOrderingCouldApply() {
        JsonNode questions = sentBody("Before render-batch.", ALL, CANDIDATES, "nightly-etl").get("questions");

        assertThat(questions.has("this_job_first")).isTrue();
    }

    @Test
    void asksTheDirectionQuestionWhenOnlyDependencyIsOffered() {
        // One question covers both directions, so offering either one has to reach it - otherwise a
        // caller that only accepts dependencies would never learn which way round the ordering goes.
        JsonNode questions = sentBody("After nightly-etl.", Set.of(ConstraintType.DEPENDENCY),
                CANDIDATES, "this-job").get("questions");

        assertThat(questions.has("states_precedence")).isTrue();
        assertThat(questions.has("this_job_first")).isTrue();
    }

    @Test
    void omitsTheDirectionQuestionWhenNoOrderingCouldApply() {
        JsonNode questions = sentBody("Finish by 6pm.", Set.of(ConstraintType.DEADLINE),
                CANDIDATES, null).get("questions");

        assertThat(questions.has("this_job_first")).isFalse();
    }

    @Test
    void returnsADependencyWhenTheOtherJobGoesFirst() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body("""
                {"states_any_constraint":{"type":"noul","noul":0.94},
                 "states_precedence":{"type":"noul","noul":0.94},
                 "this_job_first":{"type":"noul","noul":0.08},
                 "refers_to_another_job":{"type":"noul","noul":0.90},
                 "referenced_job":{"type":"choice","choice":"101","confidence":0.9,
                                   "probabilities":{"100":0.01,"101":0.99}}}"""),
                MediaType.APPLICATION_JSON));

        assertThat(classifier.classify("Run it after render-batch is done.", ALL, CANDIDATES, "this-job"))
                .singleElement().satisfies(proposal -> {
                    assertThat(proposal.type()).isEqualTo(ConstraintType.DEPENDENCY);
                    assertThat(proposal.referent()).isEqualTo("render-batch");
                });
    }

    @Test
    void aDependencyIsDroppedWhenTheCallerOnlyAcceptsPrecedence() {
        // The direction question is asked whenever either is offered, but the answer must still be
        // filtered: a caller that cannot apply a dependency must not be handed one.
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body("""
                {"states_any_constraint":{"type":"noul","noul":0.94},
                 "states_precedence":{"type":"noul","noul":0.94},
                 "this_job_first":{"type":"noul","noul":0.08},
                 "refers_to_another_job":{"type":"noul","noul":0.90},
                 "referenced_job":{"type":"choice","choice":"101","confidence":0.9,
                                   "probabilities":{"100":0.01,"101":0.99}}}"""),
                MediaType.APPLICATION_JSON));

        assertThat(classifier.classify("Run it after render-batch is done.",
                Set.of(ConstraintType.PRECEDENCE), CANDIDATES, "this-job")).isEmpty();
    }

    @Test
    void omitsTheReferentQuestionsWhenThereAreNoOtherJobs() {
        JsonNode sent = sentBody("Finish by 6pm.", ALL, List.of(), null);

        assertThat(sent.get("questions").has("referenced_job")).isFalse();
        assertThat(sent.get("questions").has("refers_to_another_job")).isFalse();
    }

    @Test
    void offersEachCandidateAsAnOptionKeyedByItsId() {
        JsonNode sent = sentBody("Before render-batch.", ALL, CANDIDATES, "nightly-etl");

        JsonNode options = sent.get("questions").get("referenced_job").get("criteria");
        assertThat(options.properties()).extracting(e -> e.getKey()).containsExactly("100", "101");
        assertThat(options.get("101").asText()).contains("render-batch");
    }

    @Test
    void keepsTheCandidateListOutOfTheState() {
        // The list in the state anchors the answer to its first entry instead of the message - see
        // ConstraintQuestions#referencedJobOption. This is the regression guard for that.
        JsonNode state = sentBody("Before render-batch.", ALL, CANDIDATES, "nightly-etl").get("state");

        assertThat(state.has("operator_jobs")).isFalse();
        assertThat(state.get("message").asText()).isEqualTo("Before render-batch.");
        assertThat(state.get("job_being_edited").get("name").asText()).isEqualTo("nightly-etl");
    }

    @Test
    void neverReturnsATypeThatWasNotOffered() {
        // Belt and braces over the questions actually sent: if a stray answer arrived for a type
        // the caller did not offer, it must not reach the guardrail as a proposal.
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body("""
                {"states_any_constraint":{"type":"noul","noul":0.95},
                 "states_deadline":{"type":"noul","noul":0.99},
                 "states_flexibility":{"type":"noul","noul":0.98}}"""), MediaType.APPLICATION_JSON));

        List<ClassifiedConstraint> result =
                classifier.classify("Finish by 6pm.", Set.of(ConstraintType.DEADLINE));

        assertThat(result).extracting(ClassifiedConstraint::type).containsExactly(ConstraintType.DEADLINE);
    }

    @Test
    void readsTheResponseIntoProposals() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body("""
                {"states_any_constraint":{"type":"noul","noul":0.95},
                 "states_deadline":{"type":"noul","noul":0.99}}"""), MediaType.APPLICATION_JSON));

        assertThat(classifier.classify("Finish by 6pm tomorrow.", ALL)).singleElement()
                .satisfies(proposal -> {
                    assertThat(proposal.type()).isEqualTo(ConstraintType.DEADLINE);
                    assertThat(proposal.phrase()).isEqualTo("Finish by 6pm tomorrow.");
                });
    }

    @Test
    void blankTextIsAnsweredWithoutCallingTheApi() {
        // Nothing to classify, so nothing to bill. server.verify() would fail on an unexpected call.
        assertThat(classifier.classify("   ", ALL)).isEmpty();
        assertThat(classifier.classify(null, ALL)).isEmpty();
        server.verify();
    }

    @Test
    void serverErrorThrows_RatherThanReadingAsNoConstraints() {
        // The whole point of failing closed: an empty list is a valid answer meaning "this message
        // asks for nothing", and a dropped call must never be mistaken for one. The caller falls
        // back to the rule-based parser and can say so.
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());

        assertThatThrownBy(() -> classifier.classify("Finish by 6pm.", ALL))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void unparseableBodyThrows() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("not json at all", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> classifier.classify("Finish by 6pm.", ALL))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void emptyBodyThrows() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess().body(new byte[0]).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> classifier.classify("Finish by 6pm.", ALL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aResponseMissingEveryAnswerReadsAsNoConstraints() {
        // Distinct from a failure: the call succeeded, so "no constraints" is a real answer here.
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"model\":\"jev-1.14.0\"}", MediaType.APPLICATION_JSON));

        assertThat(classifier.classify("Finish by 6pm.", ALL)).isEmpty();
    }

    /** Wraps answers in the response envelope the API returns. */
    private static String body(String answers) {
        return "{\"model\":\"jev-1.14.0\",\"answers\":" + answers
                + ",\"usage\":{\"input_tokens\":577,\"output_tokens\":59}}";
    }

    private JsonNode sentBody(String text, Set<ConstraintType> allowed,
                              List<JobReferenceResolver.Candidate> candidates, String editingJob) {
        try {
            return json.valueToTree(classifier.requestBody(text, allowed, candidates, editingJob));
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }
}
