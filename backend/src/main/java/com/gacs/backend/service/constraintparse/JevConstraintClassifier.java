package com.gacs.backend.service.constraintparse;

import com.gacs.backend.service.allocation.ConstraintType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calls TypeSafe's System One endpoint and turns the answers into constraint proposals.
 *
 * <p>Request shape, as the API defines it: a {@code model}, a {@code state} object, and a
 * {@code questions} map keyed by ids the caller chooses, whose keys the answers come back under.
 * The wording of every question lives in {@link ConstraintQuestions}; reading the answers lives in
 * {@link JevAnswerReader}. This class only moves bytes.
 *
 * <p>Failure is never an answer. A timeout, a 500 or an unparseable body throws, so the caller can
 * fall back to the rule-based parser and say so - rather than a dropped call quietly becoming "the
 * message contains no constraints", which looks identical to a successful parse of empty text.
 */
public class JevConstraintClassifier implements ConstraintClassifier {

    private static final Logger logger = LoggerFactory.getLogger(JevConstraintClassifier.class);

    private final RestClient http;
    private final JevProperties properties;
    private final JevAnswerReader reader;

    public JevConstraintClassifier(RestClient.Builder builder, JevProperties properties,
                                   JevAnswerReader reader) {
        this.http = builder.baseUrl(properties.endpoint()).build();
        this.properties = properties;
        this.reader = reader;
    }

    @Override
    public List<ClassifiedConstraint> classify(String text, Set<ConstraintType> allowed) {
        return classify(text, allowed, List.of(), null);
    }

    /**
     * @param candidates the operator's own jobs, offered as the options for "which job does this
     *                   refer to". Only these; a constraint may never bind a job the operator does
     *                   not own. Keep the list to what is plausible - every option is sent as input
     *                   and billed, and the Choice type allows at most 255.
     * @param editingJob the job being described, for context. Null while it is still being created.
     */
    public List<ClassifiedConstraint> classify(String text, Set<ConstraintType> allowed,
                                               List<JobReferenceResolver.Candidate> candidates,
                                               String editingJob) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Object> body = requestBody(text, allowed, candidates, editingJob);
        JevAnswers answers = http.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JevAnswers.class);
        if (answers == null) {
            throw new IllegalStateException("TypeSafe returned an empty body");
        }
        if (logger.isDebugEnabled() && answers.usage() != null) {
            // The exact payload and its cost, so any result can be rerun and accounted for.
            logger.debug("Jev classified {} chars using {} input tokens", text.length(),
                    answers.usage().inputTokens());
        }
        return reader.read(answers, text, candidates).stream()
                .filter(proposal -> allowed.contains(proposal.type()))
                .toList();
    }

    /** The request exactly as the API expects it; visible for tests that assert what is sent. */
    Map<String, Object> requestBody(String text, Set<ConstraintType> allowed,
                                    List<JobReferenceResolver.Candidate> candidates, String editingJob) {
        Map<String, Object> questions = new LinkedHashMap<>();
        ConstraintQuestions.byType().forEach((id, question) -> {
            if (offers(allowed, question.type())) {
                questions.put(id, noul(question));
            }
        });
        questions.put(ConstraintQuestions.ANY_CONSTRAINT, noul(ConstraintQuestions.anyConstraint()));

        // Direction turns the one ordering question into PRECEDENCE or DEPENDENCY, so it is only
        // worth asking when an ordering could be applied and there is another job to order against.
        if (!candidates.isEmpty() && offers(allowed, ConstraintType.PRECEDENCE)) {
            questions.put(ConstraintQuestions.THIS_JOB_FIRST, noul(ConstraintQuestions.thisJobFirst()));
        }

        if (!candidates.isEmpty()) {
            // Asked speculatively: it goes in the same request as the questions that decide whether
            // it matters, because questions run in parallel and the state is paid for once.
            questions.put(ConstraintQuestions.REFERS_TO_ANOTHER_JOB,
                    noul(ConstraintQuestions.refersToAnotherJob()));
            Map<String, String> options = new LinkedHashMap<>();
            candidates.forEach(candidate -> options.put(String.valueOf(candidate.id()),
                    ConstraintQuestions.referencedJobOption(candidate.name())));
            questions.put(ConstraintQuestions.REFERENCED_JOB, Map.of(
                    "type", "choice",
                    "instructions", ConstraintQuestions.referencedJobInstructions(),
                    "criteria", options));
        }

        // The candidate jobs go in the Choice's options and nowhere else. Listing them in the state
        // as well makes the model answer the referent question from that list rather than from the
        // message - see ConstraintQuestions#referencedJobOption for the measurement.
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("message", text);
        if (editingJob != null) {
            state.put("job_being_edited", Map.of("name", editingJob));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("state", state);
        body.put("questions", questions);
        return body;
    }

    /**
     * Whether a question about {@code type} is worth asking.
     *
     * <p>PRECEDENCE and DEPENDENCY share one question - they are the two directions of a single
     * ordering, and asking them separately does not work (see
     * {@link ConstraintQuestions#thisJobFirst()}). So that question is asked when <em>either</em>
     * direction could be applied, and a caller that offers only one of them still gets an answer.
     */
    private static boolean offers(Set<ConstraintType> allowed, ConstraintType type) {
        if (type == ConstraintType.PRECEDENCE || type == ConstraintType.DEPENDENCY) {
            return allowed.contains(ConstraintType.PRECEDENCE) || allowed.contains(ConstraintType.DEPENDENCY);
        }
        return allowed.contains(type);
    }

    private static Map<String, Object> noul(ConstraintQuestions.TypeQuestion question) {
        return Map.of(
                "type", "noul",
                "instructions", question.instructions(),
                "criteria", Map.of("yes", question.yes(), "no", question.no()));
    }
}
