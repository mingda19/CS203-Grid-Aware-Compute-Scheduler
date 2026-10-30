package com.gacs.backend.service.allocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleItem;
import com.gacs.backend.model.ScheduleRun;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Calls Anthropic's Messages API directly over HTTP (no SDK dependency - this repo had zero LLM
 * client infrastructure before this class). Structured output is enforced the way Anthropic
 * supports it: a single tool with a strict {@code input_schema}, with {@code tool_choice} forced to
 * that tool, so a successful call's {@code input} is already a parsed JSON object matching
 * {@link LlmExplanationResponse}'s shape - no second string-to-JSON parse needed, unlike
 * function-calling APIs that return arguments as a JSON-encoded string.
 */
final class AnthropicLlmExplanationClient implements LlmExplanationClient {

    private static final String DEFAULT_BASE_URI = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String TOOL_NAME = "submit_schedule_explanation";
    private static final int MAX_TOKENS = 2048;

    private final String apiKey;
    private final String modelName;
    private final String baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    AnthropicLlmExplanationClient(String apiKey, String modelName) {
        this(apiKey, modelName, DEFAULT_BASE_URI);
    }

    /** @param baseUri overridable for tests; production always uses the real Anthropic endpoint */
    AnthropicLlmExplanationClient(String apiKey, String modelName, String baseUri) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.baseUri = baseUri;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public ExplanationGenerationResult generate(ScheduleRun run, List<ScheduleItem> items,
                                                List<ConstraintImpact> impacts, Duration timeout) {
        String requestBody;
        try {
            requestBody = buildRequestBody(run, items, impacts);
        } catch (RuntimeException e) {
            return ExplanationGenerationResult.failure(FallbackReason.PROVIDER_ERROR, null);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUri))
                .timeout(timeout)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return ExplanationGenerationResult.failure(FallbackReason.TIMEOUT, null);
        } catch (Exception e) {
            return ExplanationGenerationResult.failure(FallbackReason.PROVIDER_ERROR, null);
        }

        if (response.statusCode() != 200) {
            return ExplanationGenerationResult.failure(FallbackReason.PROVIDER_ERROR, response.body());
        }
        return parse(response.body());
    }

    private ExplanationGenerationResult parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            return ExplanationGenerationResult.failure(FallbackReason.PARSE_FAILURE, body);
        }

        String stopReason = root.path("stop_reason").asText(null);
        if (!"tool_use".equals(stopReason)) {
            return ExplanationGenerationResult.failure(FallbackReason.PARSE_FAILURE, body);
        }

        for (JsonNode block : root.path("content")) {
            if ("tool_use".equals(block.path("type").asText(null)) && TOOL_NAME.equals(block.path("name").asText(null))) {
                try {
                    LlmExplanationResponse parsed = objectMapper.treeToValue(block.path("input"),
                            LlmExplanationResponse.class);
                    return ExplanationGenerationResult.success(parsed, body);
                } catch (Exception e) {
                    return ExplanationGenerationResult.failure(FallbackReason.PARSE_FAILURE, body);
                }
            }
        }
        return ExplanationGenerationResult.failure(FallbackReason.PARSE_FAILURE, body);
    }

    private String buildRequestBody(ScheduleRun run, List<ScheduleItem> items, List<ConstraintImpact> impacts) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("max_tokens", MAX_TOKENS);
        root.putArray("messages").addObject().put("role", "user").put("content", prompt(run, items, impacts));
        root.putArray("tools").add(toolDefinition());
        root.putObject("tool_choice").put("type", "tool").put("name", TOOL_NAME);
        return root.toString();
    }

    private String prompt(ScheduleRun run, List<ScheduleItem> items, List<ConstraintImpact> impacts) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("runId", run.getRunId());
        data.put("status", run.getStatus());
        data.put("provenOptimal", run.isProvenOptimal());
        data.put("baselineCostUsd", run.getBaselineCostUsd());
        data.put("optimizedCostUsd", run.getOptimizedCostUsd());
        data.put("projectedSavingsUsd", run.getProjectedSavingsUsd());
        data.put("projectedSavingsPercent", run.getProjectedSavingsPercent());

        ArrayNode jobs = data.putArray("jobs");
        for (ScheduleItem item : items) {
            ObjectNode job = jobs.addObject();
            job.put("jobId", item.getJobId());
            job.put("jobName", item.getJobName());
            job.put("status", item.getPerJobStatus());
            putNullable(job, "reason", item.getReason());
            putNullable(job, "baselineCostUsd", item.getBaselineCostUsd());
            putNullable(job, "optimizedCostUsd", item.getOptimizedCostUsd());
            putNullable(job, "savingsUsd", item.getSavingsUsd());
        }

        ArrayNode constraintImpacts = data.putArray("constraintImpacts");
        for (ConstraintImpact impact : impacts) {
            ObjectNode node = constraintImpacts.addObject();
            node.put("jobId", jobIdOf(items, impact));
            node.put("constraintType", impact.getConstraintType());
            node.put("code", impact.getCode());
            putNullable(node, "slackHours", impact.getSlackHours());
            node.put("relatedJobIds", impact.getRelatedJobIds() == null ? "" : impact.getRelatedJobIds());
        }

        return String.format(Locale.ROOT, """
                You are writing the plain-language explanation an operator reads before approving a \
                compute-job schedule. Every number below is already final and correct - never restate, \
                recompute, or contradict a number; your only job is to narrate these already-known facts \
                in clear, concise prose.

                Call the submit_schedule_explanation tool exactly once with:
                - headline: one sentence summarising the run.
                - narrative: a short paragraph giving the overall picture (cost outcome, how many jobs moved).
                - jobExplanations: one entry per job below (same jobId), explaining why it ended up where it did.
                - constraintExplanations: one entry per constraint impact below (same jobId, constraintType, \
                  and relatedJobIds, copied exactly), explaining that specific constraint's effect on that job.

                Data for this run:
                %s
                """, data.toPrettyString());
    }

    private static long jobIdOf(List<ScheduleItem> items, ConstraintImpact impact) {
        return items.stream()
                .filter(item -> item.getId().equals(impact.getScheduleItemId()))
                .findFirst()
                .map(ScheduleItem::getJobId)
                .orElseThrow(() -> new IllegalStateException(
                        "constraint impact " + impact.getId() + " has no matching schedule item"));
    }

    private static void putNullable(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private JsonNode toolDefinition() {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Submit the structured plain-language explanation for this schedule run.");

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("headline").put("type", "string");
        properties.putObject("narrative").put("type", "string");

        ObjectNode jobExplanationItems = properties.putObject("jobExplanations").put("type", "array")
                .putObject("items");
        jobExplanationItems.put("type", "object");
        ObjectNode jobExplanationProps = jobExplanationItems.putObject("properties");
        jobExplanationProps.putObject("jobId").put("type", "integer");
        jobExplanationProps.putObject("explanation").put("type", "string");
        jobExplanationItems.putArray("required").add("jobId").add("explanation");

        ObjectNode constraintExplanationItems = properties.putObject("constraintExplanations").put("type", "array")
                .putObject("items");
        constraintExplanationItems.put("type", "object");
        ObjectNode constraintExplanationProps = constraintExplanationItems.putObject("properties");
        constraintExplanationProps.putObject("jobId").put("type", "integer");
        ArrayNode constraintTypeEnum = constraintExplanationProps.putObject("constraintType")
                .put("type", "string").putArray("enum");
        for (ConstraintType type : ConstraintType.values()) {
            constraintTypeEnum.add(type.name());
        }
        constraintExplanationProps.putObject("relatedJobIds").put("type", "array")
                .putObject("items").put("type", "integer");
        constraintExplanationProps.putObject("explanation").put("type", "string");
        constraintExplanationItems.putArray("required").add("jobId").add("constraintType").add("relatedJobIds")
                .add("explanation");

        schema.putArray("required").add("headline").add("narrative").add("jobExplanations")
                .add("constraintExplanations");
        tool.set("input_schema", schema);
        return tool;
    }
}
