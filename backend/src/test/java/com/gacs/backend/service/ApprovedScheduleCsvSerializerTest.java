package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GRID-135 AC1 / AC4.1 — deterministic approved-schedule CSV (docs/api-contract.md section 6.6).
 */
class ApprovedScheduleCsvSerializerTest {

    private static final String RUN_ID = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44";

    private final ApprovedScheduleCsvSerializer serializer = new ApprovedScheduleCsvSerializer();

    private static LocalDateTime t(String iso) {
        return LocalDateTime.parse(iso);
    }

    private static ScheduleItem scheduled(long jobId, String name, String status, Allocation... slots) {
        return new ScheduleItem(jobId, name, t("2026-10-20T10:00:00"), slots[0].intervalStartUtc(),
                slots[slots.length - 1].intervalStartUtc().plusHours(1), 0.0, 0.0, List.of(slots), status, null,
                1.0, 0.5, 0.5);
    }

    private static ScheduleItem unscheduled(long jobId, String name, String reason) {
        return new ScheduleItem(jobId, name, t("2026-10-20T10:00:00"), null, null, 0.0, 0.0, List.of(),
                "NO_FEASIBLE_SLOT", reason, null, null, null);
    }

    private static OptimizeResponseDto schedule(List<ScheduleItem> items) {
        return new OptimizeResponseDto(RUN_ID, "NEEDS_ATTENTION", true, 1.0, 0.5, 0.5, 50.0, null, false, items);
    }

    private static List<ScheduleItem> sampleItems() {
        return List.of(
                scheduled(205, "Nightly ETL", "UNCHANGED_EXEMPT",
                        new Allocation(t("2026-10-20T10:00:00"), 8.0, 0.0)),
                scheduled(101, "LLM Fine-Tuning Run #42", "OPTIMISED",
                        new Allocation(t("2026-10-21T01:00:00"), 21.333333333333332, 5.333333333333333),
                        new Allocation(t("2026-10-21T02:00:00"), 42.66666666666667, 10.666666666666666)),
                unscheduled(150, "Render batch", "cluster full between 10:00 and 18:00"));
    }

    @Test
    @DisplayName("Produces the documented header, row order, timestamps and number format")
    void serialize_ExactOutput() {
        String expected = String.join("\r\n",
                "runId,jobId,jobName,perJobStatus,intervalStartUtc,intervalEndUtc,cpuHours,gpuHours,reason",
                RUN_ID + ",101,LLM Fine-Tuning Run #42,OPTIMISED,2026-10-21T01:00:00Z,2026-10-21T02:00:00Z,21.3333,5.3333,",
                RUN_ID + ",101,LLM Fine-Tuning Run #42,OPTIMISED,2026-10-21T02:00:00Z,2026-10-21T03:00:00Z,42.6667,10.6667,",
                RUN_ID + ",150,Render batch,NO_FEASIBLE_SLOT,,,,,cluster full between 10:00 and 18:00",
                RUN_ID + ",205,Nightly ETL,UNCHANGED_EXEMPT,2026-10-20T10:00:00Z,2026-10-20T11:00:00Z,8.0000,0.0000,")
                + "\r\n";

        assertThat(serializer.serialize(schedule(sampleItems()))).isEqualTo(expected);
    }

    @Test
    @DisplayName("Output does not depend on the order of items or allocations")
    void serialize_OrderIndependent() {
        String reference = serializer.serialize(schedule(sampleItems()));
        Random random = new Random(42);
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
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(serializer.serialize(schedule(sampleItems()))).contains(",21.3333,5.3333,");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("Hours are rounded half-up to 4 places in plain notation")
    void serialize_HoursFormatting() {
        String csv = serializer.serialize(schedule(List.of(scheduled(1, "tiny", "OPTIMISED",
                new Allocation(t("2026-10-21T01:00:00"), 0.00005, 1e-9),
                new Allocation(t("2026-10-21T02:00:00"), 1234567.0, -0.0)))));

        assertThat(csv).contains(",0.0001,0.0000,").contains(",1234567.0000,0.0000,").doesNotContainPattern("\\dE[-+]?\\d");
    }

    @Test
    @DisplayName("Fields with commas, quotes or line breaks are quoted per RFC 4180")
    void serialize_EscapesFields() {
        String csv = serializer.serialize(schedule(List.of(
                scheduled(1, "Train, eval", "OPTIMISED", new Allocation(t("2026-10-21T01:00:00"), 1.0, 0.0)),
                scheduled(2, "The \"big\" one", "OPTIMISED", new Allocation(t("2026-10-21T01:00:00"), 1.0, 0.0)),
                unscheduled(3, "multi\nline", "full,\r\nall day"))));

        assertThat(csv)
                .contains(",1,\"Train, eval\",OPTIMISED,")
                .contains(",2,\"The \"\"big\"\" one\",OPTIMISED,")
                .contains(",3,\"multi\nline\",NO_FEASIBLE_SLOT,,,,,\"full,\r\nall day\"\r\n");
    }

    @Test
    @DisplayName("Leaves fields without special characters unquoted")
    void escape_PlainFieldUnchanged() {
        assertThat(ApprovedScheduleCsvSerializer.escape("  spaced name ")).isEqualTo("  spaced name ");
        assertThat(ApprovedScheduleCsvSerializer.escape("")).isEmpty();
    }

    @Test
    @DisplayName("A schedule with no items is just the header row")
    void serialize_EmptySchedule() {
        assertThat(serializer.serialize(schedule(List.of())))
                .isEqualTo(String.join(",", ApprovedScheduleCsvSerializer.HEADERS) + "\r\n");
        assertThat(serializer.serialize(schedule(null)))
                .isEqualTo(String.join(",", ApprovedScheduleCsvSerializer.HEADERS) + "\r\n");
    }

    @Test
    @DisplayName("Missing job name, status or reason are written as empty fields")
    void serialize_NullTextFields() {
        ScheduleItem item = new ScheduleItem(9L, null, t("2026-10-20T10:00:00"), null, null, 0.0, 0.0, null,
                null, null, null, null, null);
        assertThat(serializer.serialize(schedule(List.of(item)))).endsWith("\r\n" + RUN_ID + ",9,,,,,,,\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-schedule", "blank-run", "null-job-id", "duplicate-job-id", "null-slot", "nan-hours"})
    @DisplayName("Rejects schedules that cannot be serialized unambiguously")
    void serialize_InvalidInputRejected(String kind) {
        Allocation ok = new Allocation(t("2026-10-21T01:00:00"), 1.0, 0.0);
        OptimizeResponseDto input = switch (kind) {
            case "null-schedule" -> null;
            case "blank-run" -> new OptimizeResponseDto(" ", "SCHEDULED", true, 0, 0, 0, 0, null, false, List.of());
            case "null-job-id" -> schedule(List.of(new ScheduleItem(null, "x", t("2026-10-20T10:00:00"), null,
                    null, 0, 0, List.of(ok), "OPTIMISED", null, null, null, null)));
            case "duplicate-job-id" -> schedule(List.of(
                    scheduled(1, "a", "OPTIMISED", ok), scheduled(1, "b", "OPTIMISED", ok)));
            case "null-slot" -> schedule(List.of(new ScheduleItem(1L, "x", t("2026-10-20T10:00:00"), null, null,
                    0, 0, Arrays.asList(new Allocation(null, 1.0, 0.0)), "OPTIMISED", null, null, null, null)));
            default -> schedule(List.of(scheduled(1, "x", "OPTIMISED",
                    new Allocation(t("2026-10-21T01:00:00"), Double.NaN, 0.0))));
        };

        assertThatThrownBy(() -> serializer.serialize(input)).isInstanceOf(IllegalArgumentException.class);
    }
}
