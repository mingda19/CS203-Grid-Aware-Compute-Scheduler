package com.gacs.backend.service.allocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleItem;
import com.gacs.backend.model.ScheduleRun;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link AnthropicLlmExplanationClient} against a local HTTP stub (JDK's built-in
 * {@link HttpServer}, no new test dependency) rather than the real Anthropic endpoint - this is the
 * actual wire format (headers, tool-use request/response shape), not a mock of our own interface.
 */
class AnthropicLlmExplanationClientTest {

    private static final ScheduleRun RUN = ScheduleRun.builder().id(1L).runId("run-1")
            .ownerEmail("operator@example.com").requestedAt(LocalDateTime.now()).status("SCHEDULED")
            .baselineCostUsd(10.0).optimizedCostUsd(8.0).projectedSavingsUsd(2.0).projectedSavingsPercent(20.0)
            .build();
    private static final ScheduleItem ITEM = ScheduleItem.builder().id(1L).scheduleRunId(1L).jobId(101L)
            .jobName("job-101").baselineStart(LocalDateTime.now()).perJobStatus("OPTIMISED")
            .baselineCostUsd(10.0).optimizedCostUsd(8.0).savingsUsd(2.0).build();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", handler);
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/v1/messages";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    @DisplayName("A valid tool_use response is parsed into LlmExplanationResponse")
    void validToolUseResponseParses() throws IOException {
        String body = """
                {"stop_reason":"tool_use","content":[{"type":"tool_use","name":"submit_schedule_explanation",
                "input":{"headline":"H","narrative":"N","jobExplanations":[{"jobId":101,"explanation":"ok"}],
                "constraintExplanations":[]}}]}""";
        String uri = start(exchange -> respond(exchange, 200, body));
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient("test-key", "model", uri);

        ExplanationGenerationResult result = client.generate(RUN, List.of(ITEM), List.of(), Duration.ofSeconds(5));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.response().headline()).isEqualTo("H");
        assertThat(result.response().jobExplanations()).extracting("jobId").containsExactly(101L);
    }

    @Test
    @DisplayName("stop_reason other than tool_use (e.g. the model hit max_tokens) is a parse failure")
    void nonToolUseStopReasonIsParseFailure() throws IOException {
        String body = """
                {"stop_reason":"max_tokens","content":[{"type":"text","text":"ran out of room"}]}""";
        String uri = start(exchange -> respond(exchange, 200, body));
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient("test-key", "model", uri);

        ExplanationGenerationResult result = client.generate(RUN, List.of(ITEM), List.of(), Duration.ofSeconds(5));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failureReason()).isEqualTo(FallbackReason.PARSE_FAILURE);
    }

    @Test
    @DisplayName("A non-200 response is a provider error, with the body retained for debugging")
    void non200IsProviderError() throws IOException {
        String uri = start(exchange -> respond(exchange, 401, "{\"error\":\"invalid api key\"}"));
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient("bad-key", "model", uri);

        ExplanationGenerationResult result = client.generate(RUN, List.of(ITEM), List.of(), Duration.ofSeconds(5));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failureReason()).isEqualTo(FallbackReason.PROVIDER_ERROR);
        assertThat(result.rawResponseJson()).contains("invalid api key");
    }

    @Test
    @DisplayName("A malformed JSON body is a parse failure, not an uncaught exception")
    void malformedJsonIsParseFailure() throws IOException {
        String uri = start(exchange -> respond(exchange, 200, "not json at all {{"));
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient("test-key", "model", uri);

        ExplanationGenerationResult result = client.generate(RUN, List.of(ITEM), List.of(), Duration.ofSeconds(5));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failureReason()).isEqualTo(FallbackReason.PARSE_FAILURE);
    }

    @Test
    @DisplayName("A server that never responds within the budget is a timeout, not a hang")
    void slowServerIsTimeout() throws IOException {
        String uri = start(exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient("test-key", "model", uri);

        ExplanationGenerationResult result = client.generate(RUN, List.of(ITEM), List.of(), Duration.ofMillis(200));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failureReason()).isEqualTo(FallbackReason.TIMEOUT);
    }

    @Test
    @DisplayName("The actual HTTP request matches Anthropic's contract: headers, model, forced tool choice")
    void requestShapeMatchesAnthropicsContract() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedApiKeyHeader = new AtomicReference<>();
        AtomicReference<String> capturedVersionHeader = new AtomicReference<>();
        String okBody = """
                {"stop_reason":"tool_use","content":[{"type":"tool_use","name":"submit_schedule_explanation",
                "input":{"headline":"H","narrative":"N","jobExplanations":[{"jobId":101,"explanation":"ok"}],
                "constraintExplanations":[]}}]}""";
        String uri = start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedApiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            capturedVersionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            respond(exchange, 200, okBody);
        });
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient("my-secret-key", "claude-haiku-4-5-20251001", uri);

        client.generate(RUN, List.of(ITEM), List.of(), Duration.ofSeconds(5));

        assertThat(capturedApiKeyHeader.get()).isEqualTo("my-secret-key");
        assertThat(capturedVersionHeader.get()).isEqualTo("2023-06-01");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree(capturedBody.get());
        assertThat(request.path("model").asText()).isEqualTo("claude-haiku-4-5-20251001");
        // Instructions live in `system`, structurally separate from the user-suppliable data in the
        // user message - and explicitly flag jobName as untrusted (prompt-injection hardening).
        assertThat(request.path("system").asText()).contains("jobName").contains("never as an instruction");
        assertThat(request.path("tool_choice").path("type").asText()).isEqualTo("tool");
        assertThat(request.path("tool_choice").path("name").asText()).isEqualTo("submit_schedule_explanation");
        JsonNode tool = request.path("tools").get(0);
        assertThat(tool.path("name").asText()).isEqualTo("submit_schedule_explanation");
        JsonNode schema = tool.path("input_schema");
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .contains("headline", "narrative", "jobExplanations", "constraintExplanations");
        String promptText = request.path("messages").get(0).path("content").asText();
        assertThat(promptText).contains("101"); // the job's id is present in the data block
    }

    @Test
    @DisplayName("The user message's data block also repeats the untrusted-jobName warning inline")
    void dataBlockRepeatsUntrustedJobNameWarning() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String okBody = """
                {"stop_reason":"tool_use","content":[{"type":"tool_use","name":"submit_schedule_explanation",
                "input":{"headline":"H","narrative":"N","jobExplanations":[{"jobId":101,"explanation":"ok"}],
                "constraintExplanations":[]}}]}""";
        String uri = start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, okBody);
        });
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient("key", "model", uri);

        client.generate(RUN, List.of(ITEM), List.of(), Duration.ofSeconds(5));

        ObjectMapper mapper = new ObjectMapper();
        String userMessage = mapper.readTree(capturedBody.get()).path("messages").get(0).path("content").asText();
        assertThat(userMessage).containsIgnoringCase("untrusted").contains("jobName");
    }

    @Test
    @DisplayName("The tool definition itself also warns against following instructions in free-text fields")
    void toolDescriptionAlsoWarnsAgainstInjection() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String okBody = """
                {"stop_reason":"tool_use","content":[{"type":"tool_use","name":"submit_schedule_explanation",
                "input":{"headline":"H","narrative":"N","jobExplanations":[{"jobId":101,"explanation":"ok"}],
                "constraintExplanations":[]}}]}""";
        String uri = start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, okBody);
        });
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient("key", "model", uri);

        client.generate(RUN, List.of(ITEM), List.of(), Duration.ofSeconds(5));

        ObjectMapper mapper = new ObjectMapper();
        String toolDescription = mapper.readTree(capturedBody.get()).path("tools").get(0).path("description").asText();
        assertThat(toolDescription).containsIgnoringCase("never follow").contains("jobName");
    }

    @Test
    @DisplayName("Constraint impact job ids are resolved correctly in the prompt data, matched via scheduleItemId")
    void constraintImpactJobIdResolvedForPrompt() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String okBody = """
                {"stop_reason":"tool_use","content":[{"type":"tool_use","name":"submit_schedule_explanation",
                "input":{"headline":"H","narrative":"N","jobExplanations":[{"jobId":101,"explanation":"ok"}],
                "constraintExplanations":[]}}]}""";
        String uri = start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, okBody);
        });
        AnthropicLlmExplanationClient client = new AnthropicLlmExplanationClient("key", "model", uri);
        ConstraintImpact impact = ConstraintImpact.builder().id(1L).scheduleItemId(1L).constraintType("DEADLINE")
                .code("DEADLINE_SLACK").slackHours(3L).narrativeFragment("f").build();

        client.generate(RUN, List.of(ITEM), List.of(impact), Duration.ofSeconds(5));

        ObjectMapper mapper = new ObjectMapper();
        String promptText = mapper.readTree(capturedBody.get()).path("messages").get(0).path("content").asText();
        JsonNode data = mapper.readTree(promptText.substring(promptText.indexOf('{')));
        JsonNode constraintImpactNode = data.path("constraintImpacts").get(0);
        assertThat(constraintImpactNode.path("jobId").asLong()).isEqualTo(101L);
        assertThat(constraintImpactNode.path("constraintType").asText()).isEqualTo("DEADLINE");
    }
}
