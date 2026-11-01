package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rules shared by the approved-schedule CSV and JSON exports (docs/api-contract.md section 6.6), so both
 * formats validate, order, round and format a schedule the same way.
 */
final class ApprovedScheduleExport {

    static final int HOURS_SCALE = 4;
    static final int USD_SCALE = 4;
    static final int PERCENT_SCALE = 2;

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private ApprovedScheduleExport() {}

    /**
     * Checks the schedule can be exported unambiguously.
     *
     * @throws IllegalArgumentException if the schedule is null, the run id is blank, a job id is missing or
     *                                  repeated, or a slot start is missing
     */
    static void validate(OptimizeResponseDto schedule) {
        if (schedule == null) {
            throw new IllegalArgumentException("schedule is required");
        }
        if (schedule.runId() == null || schedule.runId().isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        Set<Long> seen = new HashSet<>();
        for (ScheduleItem item : items(schedule)) {
            if (item.jobId() == null) {
                throw new IllegalArgumentException("Every schedule item needs a jobId");
            }
            if (!seen.add(item.jobId())) {
                throw new IllegalArgumentException("Duplicate jobId in schedule: " + item.jobId());
            }
            if (allocations(item).stream().anyMatch(a -> a.intervalStartUtc() == null)) {
                throw new IllegalArgumentException("Allocation without intervalStartUtc for jobId " + item.jobId());
            }
        }
    }

    /** Items sorted by job id, so output does not depend on request order. */
    static List<ScheduleItem> sortedItems(OptimizeResponseDto schedule) {
        return items(schedule).stream().sorted(Comparator.comparing(ScheduleItem::jobId)).toList();
    }

    /** Allocations sorted by slot start. */
    static List<Allocation> sortedAllocations(ScheduleItem item) {
        return allocations(item).stream().sorted(Comparator.comparing(Allocation::intervalStartUtc)).toList();
    }

    /** Section 2.4 format, {@code YYYY-MM-DDTHH:mm:ssZ}; null stays null. */
    static String utc(LocalDateTime time) {
        return time == null ? null : UTC.format(time);
    }

    /** The same format for an instant, in UTC, truncated to whole seconds; null stays null. */
    static String utc(Instant instant) {
        return instant == null ? null : UTC.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /** One slot is one hour (section 6.4). */
    static String slotEnd(LocalDateTime slotStart) {
        return UTC.format(slotStart.plusHours(1));
    }

    /**
     * Fixed scale, half-up, independent of the JVM locale.
     *
     * @throws IllegalArgumentException if the value is NaN or infinite
     */
    static BigDecimal decimal(double value, int scale) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Numeric values must be finite, was " + value);
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    /** As {@link #decimal(double, int)}; null stays null. */
    static BigDecimal decimal(Double value, int scale) {
        return value == null ? null : decimal(value.doubleValue(), scale);
    }

    private static List<ScheduleItem> items(OptimizeResponseDto schedule) {
        return schedule.items() == null ? List.of() : schedule.items();
    }

    private static List<Allocation> allocations(ScheduleItem item) {
        return item.allocations() == null ? List.of() : item.allocations();
    }
}
