package com.gacs.backend.service.allocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.config.DotenvLoader;
import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleItem;
import com.gacs.backend.model.ScheduleRun;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Repeated calls to the REAL Anthropic API - not the wire-level fake
 * {@code HttpServer} in {@link AnthropicLlmExplanationClientTest}, which never leaves this
 * machine and tells us nothing about the model's actual behaviour. Skipped entirely unless a real
 * key is present: loaded the same way {@code BackendApplication.main()} does ({@link DotenvLoader},
 * repo-root {@code .env}), not {@code @EnabledIfEnvironmentVariable} - that annotation only sees
 * real OS environment variables, and DotenvLoader deliberately sets a system property instead (see
 * its own class, and {@link ExplanationConfiguration}, which reads the same property).
 *
 * <p>Every assertion here is on STRUCTURE, never on wording: field presence, list sizes against
 * the real fixtures, and {@link ExplanationValidator}'s referential checks (every job id and
 * constraint impact in the fixtures is accounted for, nothing fabricated) - the same checks
 * {@link ExplanationGenerator} runs on every real request. The model's prose is explicitly never
 * pinned down; LLM output is non-deterministic, so a single run proves nothing about reliability
 * either way, which is why this makes {@value #TRIALS} independent calls and reports a rate
 * instead of a single pass/fail.
 *
 * <p>The fixtures deliberately include one {@code SYNCHRONIZED_FINISH} impact with THREE
 * {@code relatedJobIds} - this probes a specific latent risk found by auditing the prompt: the
 * data block sends {@code relatedJobIds} as a comma-separated string (straight from
 * {@code ConstraintImpact.relatedJobIds}), but the tool schema asks for it back as a JSON array of
 * integers, and the system prompt tells the model to copy it "exactly". A single-id case can't
 * distinguish "copied correctly" from "coincidentally the same either way"; three ids can.
 *
 * <p>Opt-in on purpose, with two independent gates (see {@link #loadRealApiKey()}): a real key
 * being present is not the same as someone asking to spend money on it. Run with:
 * {@code mvn test -Dtest=AnthropicLlmExplanationClientLiveTest -DrunLiveLlmTests=true}
 */
class AnthropicLlmExplanationClientLiveTest {

    private static final int TRIALS = 15;
    private static final Duration TIMEOUT = Duration.ofSeconds(15); // generous - measuring validity here, not latency
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static String apiKey;

    @BeforeAll
    static void loadRealApiKey() {
        // Two independent gates, both required: a real key alone is not consent to spend money on
        // every `mvn test` run. A developer (or CI) can have ANTHROPIC_API_KEY set for an unrelated
        // reason - e.g. running the backend locally to try the real LLM feature by hand - and would
        // otherwise trigger TRIALS real paid calls on every plain test run without ever asking for
        // this specific test. -DrunLiveLlmTests=true is the explicit ask this test actually needs.
        assumeTrue(Boolean.getBoolean("runLiveLlmTests"),
                "Live Anthropic calls are opt-in - rerun with -DrunLiveLlmTests=true (see class javadoc)");
        DotenvLoader.load();
        apiKey = System.getProperty("ANTHROPIC_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "No ANTHROPIC_API_KEY in .env - skipping live Anthropic calls (see class javadoc)");
    }

    private static ScheduleRun fixtureRun() {
        return ScheduleRun.builder()
                .id(1L).runId("11111111-1111-1111-1111-111111111111").ownerEmail("ops@example.com")
                .requestedAt(LocalDateTime.of(2026, 11, 4, 9, 0)).status("COMPLETED").provenOptimal(true)
                .baselineCostUsd(420.00).optimizedCostUsd(301.50).projectedSavingsUsd(118.50)
                .projectedSavingsPercent(28.2).usedSeededFallbackPrices(false)
                .build();
    }

    private static List<ScheduleItem> fixtureItems() {
        return List.of(
                ScheduleItem.builder().id(10L).scheduleRunId(1L).jobId(101L).jobName("nightly-batch-etl")
                        .baselineStart(LocalDateTime.of(2026, 11, 5, 2, 0))
                        .scheduledStart(LocalDateTime.of(2026, 11, 5, 4, 0))
                        .scheduledEnd(LocalDateTime.of(2026, 11, 5, 6, 0))
                        .perJobStatus("OPTIMISED").baselineCostUsd(180.00).optimizedCostUsd(96.00)
                        .savingsUsd(84.00).build(),
                ScheduleItem.builder().id(11L).scheduleRunId(1L).jobId(102L).jobName("llm-eval-sweep")
                        .baselineStart(LocalDateTime.of(2026, 11, 5, 8, 0))
                        .scheduledStart(LocalDateTime.of(2026, 11, 5, 8, 0))
                        .scheduledEnd(LocalDateTime.of(2026, 11, 5, 11, 0))
                        .perJobStatus("UNCHANGED_EXEMPT").reason("exempt").baselineCostUsd(150.00)
                        .optimizedCostUsd(150.00).savingsUsd(0.00).build(),
                ScheduleItem.builder().id(12L).scheduleRunId(1L).jobId(103L).jobName("gpu-render-farm")
                        .baselineStart(LocalDateTime.of(2026, 11, 5, 14, 0))
                        .perJobStatus("NO_FEASIBLE_SLOT").reason("no feasible slot before deadline")
                        .baselineCostUsd(90.00).build());
    }

    private static List<ConstraintImpact> fixtureImpacts() {
        return List.of(
                ConstraintImpact.builder().id(100L).scheduleItemId(10L).constraintType(ConstraintType.DEADLINE.name())
                        .code("DEADLINE_SLACK").slackHours(6L).narrativeFragment("").build(),
                ConstraintImpact.builder().id(101L).scheduleItemId(11L)
                        .constraintType(ConstraintType.FLEXIBILITY_WINDOW.name()).code("FLEX_WINDOW_SLACK")
                        .slackHours(0L).narrativeFragment("").build(),
                // The multi-id probe (see class javadoc): 3 related ids, sent as "101,102,103".
                ConstraintImpact.builder().id(102L).scheduleItemId(12L)
                        .constraintType(ConstraintType.SYNCHRONIZED_FINISH.name()).code("SYNC_FINISH_GROUP")
                        .relatedJobIds("101,102,103").narrativeFragment("").build());
    }

    @Test
    void realResponsesMatchTheSchemaAndReferenceTheRealFixtures() {
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient(apiKey, "claude-haiku-4-5-20251001");
        ScheduleRun run = fixtureRun();
        List<ScheduleItem> items = fixtureItems();
        List<ConstraintImpact> impacts = fixtureImpacts();

        int transportSuccesses = 0;
        int fullyValid = 0;
        Map<FallbackReason, Integer> transportFailuresByReason = new TreeMap<>();
        Map<String, Integer> validationFailuresByCheck = new TreeMap<>();
        List<String> sampleErrors = new ArrayList<>();
        List<String> transportFailureDiagnostics = new ArrayList<>();

        for (int trial = 1; trial <= TRIALS; trial++) {
            ExplanationGenerationResult result = client.generate(run, items, impacts, TIMEOUT);

            if (!result.succeeded()) {
                transportFailuresByReason.merge(result.failureReason(), 1, Integer::sum);
                // PARSE_FAILURE's actual cause (truncation vs. a genuine refusal vs. something
                // else) is invisible without this: AnthropicLlmExplanationClient.parse() collapses
                // every non-"tool_use" case to the same FallbackReason, by design (the fallback
                // path doesn't need to distinguish them) - but diagnosing WHY needs the body it
                // discards. ExplanationGenerationResult.rawResponseJson() carries it through.
                transportFailureDiagnostics.add(diagnose(trial, result));
                continue;
            }
            transportSuccesses++;
            LlmExplanationResponse response = result.response();

            // Structural shape, independent of ExplanationValidator: these fields are non-null by
            // the record's own compact constructor (see LlmExplanationResponse), but a Jackson
            // deserialization that silently dropped the model's actual content into empty
            // defaults would pass that constructor too - the size checks below are what actually
            // distinguishes "the model answered" from "the model's answer vanished".
            assertThat(response.headline()).isNotNull();
            assertThat(response.narrative()).isNotNull();
            assertThat(response.jobExplanations()).isNotNull();
            assertThat(response.constraintExplanations()).isNotNull();

            List<String> errors = ExplanationValidator.validate(response, items, impacts);
            if (errors.isEmpty()) {
                fullyValid++;
            } else {
                errors.forEach(error -> {
                    String check = error.split(" ", 3)[0] + " " + error.replaceAll("\\d+", "N").split(" ", 4)[1];
                    validationFailuresByCheck.merge(check, 1, Integer::sum);
                });
                if (sampleErrors.size() < 5) {
                    sampleErrors.add("trial " + trial + ": " + errors);
                }
            }
        }

        System.out.printf("%n=== Live Anthropic trial report (%d trials) ===%n", TRIALS);
        System.out.printf("Transport succeeded (parsed as tool_use): %d/%d%n", transportSuccesses, TRIALS);
        transportFailuresByReason.forEach((reason, count) -> System.out.printf("  transport failure %s: %d%n", reason, count));
        transportFailureDiagnostics.forEach(System.out::println);
        System.out.printf("Fully valid (ExplanationValidator: zero errors): %d/%d%n", fullyValid, TRIALS);
        validationFailuresByCheck.forEach((check, count) -> System.out.printf("  validation issue [%s]: %d trial(s)%n", check, count));
        sampleErrors.forEach(System.out::println);
        System.out.println("=== end report ===");

        // Corrected after a real run: even with forced tool_choice, an occasional PARSE_FAILURE
        // (the model not completing the tool call cleanly) turned out to be real LLM variance, not
        // only a wiring bug - a hard zero-tolerance assertion here was wrong and would make this
        // test spuriously red on ordinary noise. Both layers are therefore rate-based, not hard
        // guarantees - this test's job is to measure and report reliability, not assume it's 100%.
        assertThat(transportSuccesses).as("transport (parses as tool_use) success rate %d/%d - "
                + "see the per-reason breakdown printed above", transportSuccesses, TRIALS)
                .isGreaterThanOrEqualTo((int) Math.ceil(TRIALS * 0.7));

        // Referential/shape validity (ExplanationValidator) depends on the model actually
        // following instructions - not guaranteed by the schema alone. Still reported and
        // thresholded (not just logged) so a systemic break - e.g. the relatedJobIds CSV-vs-array
        // risk this test's fixtures specifically probe for, if it started actually manifesting -
        // fails this test loudly instead of silently degrading to the deterministic fallback.
        assertThat(fullyValid).as("fully-valid rate %d/%d - see the per-check breakdown printed above",
                fullyValid, TRIALS).isGreaterThanOrEqualTo((int) Math.ceil(TRIALS * 0.7));
    }

    /** {@code stop_reason} plus a short excerpt of whatever the model actually produced, pulled
     *  straight from the raw response body - the one piece of information
     *  AnthropicLlmExplanationClient.parse() throws away once it's decided "not tool_use, so
     *  PARSE_FAILURE" (that collapse is correct for the fallback path, which has no use for the
     *  distinction; it just means diagnosing the cause needs the raw body instead). Falls back to
     *  a note that no body exists at all (the request-build/exception path, not a server response -
     *  see AnthropicLlmExplanationClient.generate's exception mapping) rather than failing to parse. */
    private static String diagnose(int trial, ExplanationGenerationResult result) {
        String raw = result.rawResponseJson();
        if (raw == null) {
            return "  trial " + trial + " [" + result.failureReason() + "]: no response body (request-build "
                    + "failure or transport exception before any HTTP response)";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            String stopReason = root.path("stop_reason").asText("(absent)");
            StringBuilder excerpt = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                String type = block.path("type").asText("?");
                if ("text".equals(type)) {
                    String text = block.path("text").asText("");
                    excerpt.append("[text: ").append(text.substring(0, Math.min(text.length(), 150))).append("] ");
                } else if ("tool_use".equals(type)) {
                    // Reproduce AnthropicLlmExplanationClient.parse()'s own deserialization
                    // attempt on this exact block - its try/catch swallows the Jackson exception
                    // entirely, so this is the only way to see what it actually was.
                    JsonNode input = block.path("input");
                    String name = block.path("name").asText("?");
                    try {
                        OBJECT_MAPPER.treeToValue(input, LlmExplanationResponse.class);
                        excerpt.append("[tool_use name=").append(name).append(", input deserializes fine - "
                                + "PARSE_FAILURE must be from a different block/condition] ");
                    } catch (Exception deserializeFailure) {
                        excerpt.append("[tool_use name=").append(name).append(", input FAILS to deserialize: ")
                                .append(deserializeFailure).append(" | raw input=").append(input).append("] ");
                    }
                } else {
                    excerpt.append("[").append(type).append("] ");
                }
            }
            return "  trial " + trial + " [" + result.failureReason() + "]: stop_reason=" + stopReason
                    + ", content=" + excerpt;
        } catch (Exception e) {
            String body = raw.substring(0, Math.min(raw.length(), 300));
            return "  trial " + trial + " [" + result.failureReason() + "]: raw body isn't JSON - first 300 "
                    + "chars: " + body;
        }
    }
}
