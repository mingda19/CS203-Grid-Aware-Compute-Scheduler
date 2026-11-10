package com.gacs.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.ApprovedScheduleDocument;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.model.OptimizerRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GRID-135 AC2 / AC4.2 — approved-schedule JSON with complete run metadata and per-job slots
 * (docs/api-contract.md section 6.6).
 */
class ApprovedScheduleJsonSerializerTest {

    private static final String RUN_ID = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44";

    private final ApprovedScheduleJsonSerializer serializer = new ApprovedScheduleJsonSerializer();
    private final ObjectMapper reader = new ObjectMapper();

    private static LocalDateTime t(String iso) {
        return LocalDateTime.parse(iso);
    }

    private static OptimizeResponseDto schedule(List<ScheduleItem> items) {
        return new OptimizeResponseDto(RUN_ID, "NEEDS_ATTENTION", true, 1.5752, 0.7304, 0.8448, 53.63,
                "Scheduled 2 of 3 job(s).", false, items);
    }

    private static ScheduleItem simple(long jobId, String name) {
        return new ScheduleItem(jobId, name, t("2026-10-21T01:00:00"), t("2026-10-21T01:00:00"),
                t("2026-10-21T02:00:00"), 1.0, 0.0, List.of(new Allocation(t("2026-10-21T01:00:00"), 1.0, 0.0)),
                "OPTIMISED", null, 0.1, 0.1, 0.0);
    }

    private static List<ScheduleItem> sampleItems() {
        return List.of(
                new ScheduleItem(205L, "Nightly ETL", t("2026-10-20T10:00:00"), t("2026-10-20T10:00:00"),
                        t("2026-10-20T11:00:00"), 8.0, 0.0,
                        List.of(new Allocation(t("2026-10-20T10:00:00"), 8.0, 0.0)),
                        "UNCHANGED_EXEMPT", null, 0.5, 0.5, 0.0),
                new ScheduleItem(101L, "LLM Fine-Tuning Run #42", t("2026-10-20T10:00:00"), t("2026-10-21T01:00:00"),
                        t("2026-10-21T03:00:00"), 64.0, 16.0,
                        List.of(new Allocation(t("2026-10-21T02:00:00"), 42.66666666666667, 10.666666666666666),
                                new Allocation(t("2026-10-21T01:00:00"), 21.333333333333332, 5.333333333333333)),
                        "OPTIMISED", null, 1.0752, 0.2304, 0.8448),
                new ScheduleItem(150L, "Render batch", t("2026-10-20T12:00:00"), null, null, 0.0, 0.0, List.of(),
                        "NO_FEASIBLE_SLOT", "cluster full between 10:00 and 18:00", null, null, null));
    }

    @Test
    @DisplayName("Produces the documented keys, order, timestamps and number format")
    void serialize_ExactOutput() {
        String expected = "{\"formatVersion\":1,"
                + "\"run\":{\"runId\":\"" + RUN_ID + "\",\"status\":\"NEEDS_ATTENTION\",\"provenOptimal\":true,"
                + "\"usedSeededFallbackPrices\":false,\"baselineCostUsd\":1.5752,\"optimizedCostUsd\":0.7304,"
                + "\"projectedSavingsUsd\":0.8448,\"projectedSavingsPercent\":53.63,"
                + "\"plainEnglishExplanation\":\"Scheduled 2 of 3 job(s).\",\"createdAt\":null,\"solver\":null},"
                + "\"approval\":null,"
                + "\"jobs\":["
                + "{\"jobId\":101,\"jobName\":\"LLM Fine-Tuning Run #42\",\"perJobStatus\":\"OPTIMISED\",\"reason\":null,"
                + "\"baselineStart\":\"2026-10-20T10:00:00Z\",\"scheduledStart\":\"2026-10-21T01:00:00Z\","
                + "\"scheduledEnd\":\"2026-10-21T03:00:00Z\",\"allocatedCpuHours\":64.0000,\"allocatedGpuHours\":16.0000,"
                + "\"baselineCostUsd\":1.0752,\"optimizedCostUsd\":0.2304,\"savingsUsd\":0.8448,\"rescheduledFromElapsedStart\":false,\"slots\":["
                + "{\"intervalStartUtc\":\"2026-10-21T01:00:00Z\",\"intervalEndUtc\":\"2026-10-21T02:00:00Z\","
                + "\"cpuHours\":21.3333,\"gpuHours\":5.3333},"
                + "{\"intervalStartUtc\":\"2026-10-21T02:00:00Z\",\"intervalEndUtc\":\"2026-10-21T03:00:00Z\","
                + "\"cpuHours\":42.6667,\"gpuHours\":10.6667}]},"
                + "{\"jobId\":150,\"jobName\":\"Render batch\",\"perJobStatus\":\"NO_FEASIBLE_SLOT\","
                + "\"reason\":\"cluster full between 10:00 and 18:00\",\"baselineStart\":\"2026-10-20T12:00:00Z\","
                + "\"scheduledStart\":null,\"scheduledEnd\":null,\"allocatedCpuHours\":0.0000,\"allocatedGpuHours\":0.0000,"
                + "\"baselineCostUsd\":null,\"optimizedCostUsd\":null,\"savingsUsd\":null,\"rescheduledFromElapsedStart\":false,\"slots\":[]},"
                + "{\"jobId\":205,\"jobName\":\"Nightly ETL\",\"perJobStatus\":\"UNCHANGED_EXEMPT\",\"reason\":null,"
                + "\"baselineStart\":\"2026-10-20T10:00:00Z\",\"scheduledStart\":\"2026-10-20T10:00:00Z\","
                + "\"scheduledEnd\":\"2026-10-20T11:00:00Z\",\"allocatedCpuHours\":8.0000,\"allocatedGpuHours\":0.0000,"
                + "\"baselineCostUsd\":0.5000,\"optimizedCostUsd\":0.5000,\"savingsUsd\":0.0000,\"rescheduledFromElapsedStart\":false,\"slots\":["
                + "{\"intervalStartUtc\":\"2026-10-20T10:00:00Z\",\"intervalEndUtc\":\"2026-10-20T11:00:00Z\","
                + "\"cpuHours\":8.0000,\"gpuHours\":0.0000}]}"
                + "]}";

        assertThat(serializer.serialize(schedule(sampleItems()))).isEqualTo(expected);
    }

    @Test
    @DisplayName("Run metadata carries every run-level field of the optimise response, plus the run record")
    void document_RunMetadataComplete() {
        Set<String> expected = componentNames(OptimizeResponseDto.class);
        expected.remove("items");
        expected.addAll(Set.of("createdAt", "solver"));

        assertThat(componentNames(ApprovedScheduleDocument.RunMetadata.class)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Every field of the stored run record appears in the export")
    void document_CoversEveryRunRecordField() {
        // OptimizerRun field -> where the export carries it. A new OptimizerRun field fails this test.
        // status/provenOptimal are deliberately absent: they live on ScheduleRun now, not duplicated
        // here, and the export already sources its top-level status/provenOptimal from the
        // OptimizeResponseDto `schedule` parameter, not from the run record.
        Map<String, String> carriedAs = Map.of(
                "id", "run.runId", "createdAt", "run.createdAt",
                "solverName", "run.solver.name",
                "solverVersion", "run.solver.version", "solverConfig", "run.solver.config");
        // inputSnapshot/inputHash (GRID-141) are what the export gate checks the jobs against, not part of the
        // schedule document an execution adapter reads.
        Set<String> notExported = Set.of("inputSnapshot", "inputHash");
        Set<String> every = new java.util.HashSet<>(carriedAs.keySet());
        every.addAll(notExported);

        assertThat(fieldNames(OptimizerRun.class)).isEqualTo(every);
        assertThat(componentNames(ApprovedScheduleDocument.Solver.class)).containsExactlyInAnyOrder("name", "version", "config");
    }

    @Test
    @DisplayName("Every field of the approving audit event appears in the export")
    void document_CoversEveryApprovalField() {
        // AuditEvent field -> where the export carries it; actionType is always APPROVE and runId is run.runId.
        Map<String, String> carriedAs = Map.of(
                "id", "approval.decisionId", "actor", "approval.approvedBy", "timestamp", "approval.approvedAt",
                "operatorRationale", "approval.comment", "scheduleHash", "approval.scheduleHash",
                "actionType", "(always APPROVE)", "runId", "run.runId");

        assertThat(fieldNames(AuditEvent.class)).isEqualTo(carriedAs.keySet());
    }

    private static OptimizerRun runRecord(String config) {
        return OptimizerRun.builder().id(RUN_ID).createdAt(Instant.parse("2026-10-20T09:00:00.123456Z"))
                .solverName("HybridAllocationSolver").solverVersion("1").solverConfig(config).build();
    }

    private static AuditEvent approvalEvent(AuditActionType action, String runId) {
        return AuditEvent.builder().id(42L).actor("operator@gacs.internal").actionType(action)
                .timestamp(Instant.parse("2026-10-20T09:30:15.987Z")).runId(runId)
                .scheduleHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .operatorRationale("Approved after \"cooling\" check").build();
    }

    @Test
    @DisplayName("AC4.2 — with a run record and approval, the export carries solver and approval details")
    void serialize_WithRunRecordAndApproval() throws Exception {
        String json = serializer.serialize(schedule(sampleItems()),
                runRecord("{\"cpuKwhPerHour\":0.05,\"gpuKwhPerHour\":0.4,\"timeLimit\":\"PT10S\"}"),
                approvalEvent(AuditActionType.APPROVE, RUN_ID));

        assertThat(json).contains("\"plainEnglishExplanation\":\"Scheduled 2 of 3 job(s).\","
                + "\"createdAt\":\"2026-10-20T09:00:00Z\","
                + "\"solver\":{\"name\":\"HybridAllocationSolver\",\"version\":\"1\","
                + "\"config\":{\"cpuKwhPerHour\":0.05,\"gpuKwhPerHour\":0.4,\"timeLimit\":\"PT10S\"}}},"
                + "\"approval\":{\"decisionId\":42,\"approvedBy\":\"operator@gacs.internal\","
                + "\"approvedAt\":\"2026-10-20T09:30:15Z\",\"comment\":\"Approved after \\\"cooling\\\" check\","
                + "\"scheduleHash\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"},"
                + "\"jobs\":[{\"jobId\":101,");

        ApprovedScheduleDocument document = serializer.toDocument(schedule(sampleItems()),
                runRecord("{\"cpuKwhPerHour\":0.05,\"gpuKwhPerHour\":0.4,\"timeLimit\":\"PT10S\"}"),
                approvalEvent(AuditActionType.APPROVE, RUN_ID));
        assertThat(reader.readValue(json, ApprovedScheduleDocument.class)).isEqualTo(document);
    }

    @Test
    @DisplayName("A solver config that is not valid JSON is carried as text")
    void serialize_UnreadableSolverConfigAsText() {
        String json = serializer.serialize(schedule(sampleItems()), runRecord("cpu=0.05"), null);

        assertThat(json).contains("\"config\":\"cpu=0.05\"}},\"approval\":null,");
    }

    @Test
    @DisplayName("A run record or approval from another run, or a non-approval event, is rejected")
    void serialize_MismatchedProvenanceRejected() {
        OptimizerRun otherRun = OptimizerRun.builder().id("another-run").build();

        assertThatThrownBy(() -> serializer.serialize(schedule(sampleItems()), otherRun, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serializer.serialize(schedule(sampleItems()), null,
                approvalEvent(AuditActionType.APPROVE, "another-run")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serializer.serialize(schedule(sampleItems()), null,
                approvalEvent(AuditActionType.REJECT, RUN_ID)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Each job carries every per-job field of the optimise response, with allocations as slots")
    void document_JobFieldsComplete() {
        Set<String> itemFields = componentNames(ScheduleItem.class);
        itemFields.remove("allocations");
        Set<String> jobFields = componentNames(ApprovedScheduleDocument.Job.class);

        assertThat(jobFields).containsAll(itemFields).contains("slots");
        assertThat(jobFields).hasSize(itemFields.size() + 1);
    }

    @Test
    @DisplayName("Every key is present, with null where a value is absent")
    void serialize_AllKeysPresent() throws Exception {
        JsonNode root = reader.readTree(serializer.serialize(schedule(sampleItems())));
        JsonNode unscheduled = root.get("jobs").get(1);

        assertThat(fieldNames(root)).containsExactly("formatVersion", "run", "approval", "jobs");
        // 14, not 13, since a job gained rescheduledFromElapsedStart. The count is the shape of the
        // document, which document_JobFieldsComplete requires to mirror ScheduleItem field for field.
        assertThat(fieldNames(unscheduled)).hasSize(14);
        assertThat(unscheduled.get("scheduledStart").isNull()).isTrue();
        assertThat(unscheduled.get("savingsUsd").isNull()).isTrue();
        assertThat(unscheduled.get("slots").isArray()).isTrue();
        assertThat(unscheduled.get("slots")).isEmpty();
    }

    @Test
    @DisplayName("Output does not depend on the order of items or allocations")
    void serialize_OrderIndependent() {
        String reference = serializer.serialize(schedule(sampleItems()));
        Random random = new Random(7);
        for (int i = 0; i < 20; i++) {
            List<ScheduleItem> shuffled = new ArrayList<>();
            for (ScheduleItem item : sampleItems()) {
                List<Allocation> slots = new ArrayList<>(item.allocations());
                Collections.shuffle(slots, random);
                shuffled.add(new ScheduleItem(item.jobId(), item.jobName(), item.baselineStart(),
                        item.scheduledStart(), item.scheduledEnd(), item.allocatedCpuHours(),
                        item.allocatedGpuHours(), slots, item.perJobStatus(), item.reason(),
                        item.baselineCostUsd(), item.optimizedCostUsd(), item.savingsUsd()));
            }
            Collections.shuffle(shuffled, random);
            assertThat(serializer.serialize(schedule(shuffled))).isEqualTo(reference);
        }
    }

    @Test
    @DisplayName("Numbers use a dot decimal separator whatever the default locale")
    void serialize_LocaleIndependent() {
        String reference = serializer.serialize(schedule(sampleItems()));
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(serializer.serialize(schedule(sampleItems()))).isEqualTo(reference);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("Numbers are fixed-scale plain decimals, including negatives and large values")
    void serialize_NumberFormatting() {
        ScheduleItem item = new ScheduleItem(1L, "big", t("2026-10-21T01:00:00"), t("2026-10-21T01:00:00"),
                t("2026-10-21T02:00:00"), 1234567.0, 0.00005,
                List.of(new Allocation(t("2026-10-21T01:00:00"), 1234567.0, 0.00005)),
                "OPTIMISED", null, 0.1, 0.2234, -0.1234);
        OptimizeResponseDto input = new OptimizeResponseDto(RUN_ID, "SCHEDULED", false, 1e-9, 0.2234, -0.1234,
                -123.456, null, true, List.of(item));

        String json = serializer.serialize(input);

        assertThat(json)
                .contains("\"baselineCostUsd\":0.0000,")
                .contains("\"projectedSavingsUsd\":-0.1234,")
                .contains("\"projectedSavingsPercent\":-123.46,")
                .contains("\"plainEnglishExplanation\":null")
                .contains("\"allocatedCpuHours\":1234567.0000,\"allocatedGpuHours\":0.0001,")
                .contains("\"savingsUsd\":-0.1234,")
                .doesNotContainPattern("\\dE[-+]?\\d");
    }

    @Test
    @DisplayName("Text with quotes, control characters and non-ASCII round-trips through a JSON parser")
    void serialize_EscapesText() throws Exception {
        String name = "The \"big\" one\nline two\té中 \\ /";
        String json = serializer.serialize(schedule(List.of(simple(1, name))));

        assertThat(reader.readTree(json).get("jobs").get(0).get("jobName").asText()).isEqualTo(name);
        assertThat(json).doesNotContain("\n").doesNotContain("\t");
    }

    @Test
    @DisplayName("Output is compact with no trailing newline")
    void serialize_Compact() {
        String json = serializer.serialize(schedule(sampleItems()));

        assertThat(json).startsWith("{\"formatVersion\":1,").endsWith("]}").doesNotContain("\n").doesNotContain(": ");
    }

    @Test
    @DisplayName("A schedule with no items has an empty jobs array")
    void serialize_EmptySchedule() throws Exception {
        assertThat(serializer.serialize(schedule(List.of()))).endsWith(",\"jobs\":[]}");
        assertThat(reader.readTree(serializer.serialize(schedule(null))).get("jobs")).isEmpty();
    }

    @Test
    @DisplayName("toDocument returns the same content serialize writes, and the JSON reads back to it exactly")
    void toDocument_MatchesSerialize() throws Exception {
        ApprovedScheduleDocument document = serializer.toDocument(schedule(sampleItems()));

        assertThat(document.formatVersion()).isEqualTo(ApprovedScheduleDocument.FORMAT_VERSION);
        assertThat(document.run().runId()).isEqualTo(RUN_ID);
        assertThat(document.jobs()).extracting(ApprovedScheduleDocument.Job::jobId).containsExactly(101L, 150L, 205L);
        assertThat(document.jobs().get(0).slots()).hasSize(2);
        String json = serializer.serialize(schedule(sampleItems()));
        assertThat(reader.readValue(json, ApprovedScheduleDocument.class)).isEqualTo(document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-schedule", "blank-run", "null-job-id", "duplicate-job-id", "null-slot",
        "nan-hours", "infinite-run-cost", "nan-job-cost"})
    @DisplayName("Rejects schedules that cannot be serialized unambiguously")
    void serialize_InvalidInputRejected(String kind) {
        Allocation ok = new Allocation(t("2026-10-21T01:00:00"), 1.0, 0.0);
        LocalDateTime base = t("2026-10-21T01:00:00");
        OptimizeResponseDto input = switch (kind) {
            case "null-schedule" -> null;
            case "blank-run" -> new OptimizeResponseDto("", "SCHEDULED", true, 0, 0, 0, 0, null, false, List.of());
            case "null-job-id" -> schedule(List.of(new ScheduleItem(null, "x", base, base, base.plusHours(1),
                    1, 0, List.of(ok), "OPTIMISED", null, 0.1, 0.1, 0.0)));
            case "duplicate-job-id" -> schedule(List.of(simple(1, "a"), simple(1, "b")));
            case "null-slot" -> schedule(List.of(new ScheduleItem(1L, "x", base, base, base.plusHours(1), 1, 0,
                    Arrays.asList(new Allocation(null, 1.0, 0.0)), "OPTIMISED", null, 0.1, 0.1, 0.0)));
            case "nan-hours" -> schedule(List.of(new ScheduleItem(1L, "x", base, base, base.plusHours(1), 1, 0,
                    List.of(new Allocation(base, Double.NaN, 0.0)), "OPTIMISED", null, 0.1, 0.1, 0.0)));
            case "infinite-run-cost" -> new OptimizeResponseDto(RUN_ID, "SCHEDULED", true,
                    Double.POSITIVE_INFINITY, 0, 0, 0, null, false, List.of());
            default -> schedule(List.of(new ScheduleItem(1L, "x", base, base, base.plusHours(1), 1, 0,
                    List.of(ok), "OPTIMISED", null, Double.NaN, 0.1, 0.0)));
        };

        assertThatThrownBy(() -> serializer.serialize(input)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Set<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(java.util.HashSet::new));
    }

    private static Set<String> fieldNames(Class<?> entity) {
        return Arrays.stream(entity.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
