package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static com.gacs.backend.service.ApprovedScheduleExport.HOURS_SCALE;
import static com.gacs.backend.service.ApprovedScheduleExport.decimal;

/**
 * Serializes an approved schedule to CSV for execution adapters (docs/api-contract.md section 6.6, GRID-135 AC1).
 *
 * <p>The output is deterministic: the same schedule always produces byte-identical CSV, whatever the order of
 * its items or allocations. Rows are sorted by job id and then slot start, timestamps are UTC with a {@code Z}
 * suffix (section 2.4), hours have exactly 4 decimal places, and records follow RFC 4180 with CRLF line endings.
 */
@Component
public class ApprovedScheduleCsvSerializer {

    public static final List<String> HEADERS = List.of(
            "runId", "jobId", "jobName", "perJobStatus",
            "intervalStartUtc", "intervalEndUtc", "cpuHours", "gpuHours", "reason");

    private static final String LINE_END = "\r\n";

    /**
     * @param schedule the schedule the operator approved
     * @return the CSV document, header row first, every record terminated by CRLF
     * @throws IllegalArgumentException if the run id is blank, a job id is missing or repeated, a slot start is
     *                                  missing, or an hours value is not finite
     */
    public String serialize(OptimizeResponseDto schedule) {
        ApprovedScheduleExport.validate(schedule);
        String runId = schedule.runId();

        StringBuilder csv = new StringBuilder();
        appendRecord(csv, HEADERS);
        for (ScheduleItem item : ApprovedScheduleExport.sortedItems(schedule)) {
            List<Allocation> allocations = ApprovedScheduleExport.sortedAllocations(item);
            if (allocations.isEmpty()) {
                // Unscheduled (NO_FEASIBLE_SLOT): one row so the job is still listed, with no time invented.
                appendRecord(csv, List.of(runId, item.jobId().toString(), nullToEmpty(item.jobName()),
                        nullToEmpty(item.perJobStatus()), "", "", "", "", nullToEmpty(item.reason())));
                continue;
            }
            for (Allocation slot : allocations) {
                LocalDateTime start = slot.intervalStartUtc();
                appendRecord(csv, List.of(runId, item.jobId().toString(), nullToEmpty(item.jobName()),
                        nullToEmpty(item.perJobStatus()),
                        ApprovedScheduleExport.utc(start), ApprovedScheduleExport.slotEnd(start),
                        decimal(slot.cpuHours(), HOURS_SCALE).toPlainString(),
                        decimal(slot.gpuHours(), HOURS_SCALE).toPlainString(),
                        nullToEmpty(item.reason())));
            }
        }
        return csv.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void appendRecord(StringBuilder csv, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(fields.get(i)));
        }
        csv.append(LINE_END);
    }

    /** RFC 4180: quote a field only when it contains a comma, quote, CR or LF; double embedded quotes. */
    static String escape(String field) {
        boolean needsQuotes = field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0;
        return needsQuotes ? '"' + field.replace("\"", "\"\"") + '"' : field;
    }
}
