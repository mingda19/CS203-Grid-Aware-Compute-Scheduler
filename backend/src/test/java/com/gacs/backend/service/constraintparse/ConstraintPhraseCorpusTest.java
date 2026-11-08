package com.gacs.backend.service.constraintparse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.ConstraintParseRequestDto;
import com.gacs.backend.dto.ConstraintParseRequestDto.Draft;
import com.gacs.backend.dto.ConstraintParseResponseDto;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.service.JobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Runs every phrase in {@code constraintparse/phrases.json} through the real parser, the service and the job
 * validator (docs/constraint-parser-plan.md section 4). Adding a phrase is one line in that file.
 */
class ConstraintPhraseCorpusTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIELDS = List.of("originalStartTime", "deadline", "flexibilityWindowHours",
            "isExempt", "priority");

    static Stream<Arguments> phrases() throws IOException {
        JsonNode corpus = corpus();
        List<Arguments> out = new ArrayList<>();
        int i = 0;
        for (JsonNode phrase : corpus.path("phrases")) {
            String text = phrase.path("text").asText().repeat(Math.max(1, phrase.path("repeat").asInt(1)));
            String label = String.format("%02d %s: %s", ++i, phrase.path("category").asText(),
                    text.length() > 60 ? text.substring(0, 60) + "..." : text);
            out.add(Arguments.of(label, phrase, corpus));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("phrases")
    void phrase(String label, JsonNode phrase, JsonNode corpus) {
        ZoneId zone = ZoneId.of(phrase.path("zone").asText(corpus.path("zone").asText()));
        LocalDateTime now = LocalDateTime.parse(phrase.path("now").asText(corpus.path("now").asText()));
        ConstraintParseService service = service(Clock.fixed(now.atZone(zone).toInstant(), ZoneOffset.UTC));

        String text = phrase.path("text").asText().repeat(Math.max(1, phrase.path("repeat").asInt(1)));
        ConstraintParseResponseDto result = service.parse(
                new ConstraintParseRequestDto(text, draft(phrase.path("draft")), zone.getId()));
        JsonNode expect = phrase.path("expect");

        Map<String, String> actualFields = Map.of(
                "originalStartTime", str(result.fields().originalStartTime()),
                "deadline", str(result.fields().deadline()),
                "flexibilityWindowHours", str(result.fields().flexibilityWindowHours()),
                "isExempt", str(result.fields().isExempt()),
                "priority", str(result.fields().priority()));
        for (String field : FIELDS) {
            String expected = expect.has(field) ? expect.get(field).asText() : "null";
            assertThat(actualFields.get(field)).as("%s (%s)", field, describe(result)).isEqualTo(expected);
        }

        assertThat(result.ambiguities().stream().map(a -> a.reason().name()).toList())
                .as("ambiguities (%s)", describe(result))
                .containsExactlyInAnyOrderElementsOf(texts(expect.path("ambiguities")));
        assertThat(result.conflicts().stream().map(c -> c.field().apiName()).toList())
                .as("conflicts (%s)", describe(result))
                .containsExactlyInAnyOrderElementsOf(texts(expect.path("conflicts")));
        if (expect.has("unsupported")) {
            assertThat(result.unsupported().stream().map(ParsedConstraints.Unsupported::phrase).toList())
                    .as("unsupported").containsExactlyElementsOf(texts(expect.path("unsupported")));
        }
        if (expect.has("status")) {
            assertThat(result.status()).as("status (%s)", describe(result)).isEqualTo(expect.get("status").asText());
        }
        if (expect.has("validationError")) {
            assertThat(result.validationError()).isEqualTo(expect.get("validationError").asText());
        } else {
            assertThat(result.validationError()).as("validationError").isNull();
        }
    }

    @Test
    @DisplayName("The phrase file covers every field and every category in the plan")
    void corpusCoversThePlan() throws IOException {
        JsonNode corpus = corpus();
        Map<String, Long> byCategory = stream(corpus.path("phrases"))
                .collect(Collectors.groupingBy(p -> p.path("category").asText(), Collectors.counting()));
        assertThat(byCategory.getOrDefault("supported", 0L)).isGreaterThanOrEqualTo(40);
        assertThat(byCategory.getOrDefault("ambiguous", 0L)).isGreaterThanOrEqualTo(20);
        assertThat(byCategory.getOrDefault("adversarial", 0L)).isGreaterThanOrEqualTo(25);

        List<JsonNode> supported = stream(corpus.path("phrases"))
                .filter(p -> p.path("category").asText().equals("supported")).toList();
        for (String field : FIELDS) {
            assertThat(supported).as("supported phrases for %s", field)
                    .anyMatch(p -> p.path("expect").has(field));
        }
        assertThat(stream(corpus.path("phrases")).flatMap(p -> texts(p.path("expect").path("ambiguities")).stream())
                .collect(Collectors.toSet()))
                .as("every ambiguity reason has a phrase")
                .containsAll(Stream.of(AmbiguityReason.values()).map(Enum::name).toList());
    }

    static ConstraintParseService service(Clock clock) {
        return new ConstraintParseService(new RuleBasedConstraintParser(), new JobService(mock(JobRepository.class), org.mockito.Mockito.mock(com.gacs.backend.service.ScheduleInputs.class), clock, null, null, null, null),
                clock, "America/Chicago");
    }

    private static Draft draft(JsonNode node) {
        if (node.isMissingNode()) {
            return null;
        }
        Function<String, LocalDateTime> time = f -> node.has(f) ? LocalDateTime.parse(node.get(f).asText()) : null;
        return new Draft(time.apply("originalStartTime"), time.apply("deadline"),
                node.has("flexibilityWindowHours") ? node.get("flexibilityWindowHours").asInt() : null,
                node.has("priority") ? node.get("priority").asText() : null,
                node.has("isExempt") ? node.get("isExempt").asBoolean() : null);
    }

    private static JsonNode corpus() throws IOException {
        try (InputStream in = ConstraintPhraseCorpusTest.class.getResourceAsStream("/constraintparse/phrases.json")) {
            return JSON.readTree(in);
        }
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list.stream();
    }

    private static Collection<String> texts(JsonNode array) {
        return stream(array).map(JsonNode::asText).toList();
    }

    private static String str(Object value) {
        if (value instanceof LocalDateTime time) {
            return time.withNano(0).toString().length() == 16 ? time + ":00" : time.toString();
        }
        return String.valueOf(value);
    }

    private static String describe(ConstraintParseResponseDto r) {
        return r.status() + " " + r.fields() + " amb=" + r.ambiguities().stream().map(a -> a.reason() + "@" + a.phrase()).toList()
                + " conflicts=" + r.conflicts().stream().map(c -> c.field() + c.phrases().toString()).toList()
                + " unsupported=" + r.unsupported().stream().map(ParsedConstraints.Unsupported::phrase).toList()
                + " error=" + r.validationError();
    }
}
