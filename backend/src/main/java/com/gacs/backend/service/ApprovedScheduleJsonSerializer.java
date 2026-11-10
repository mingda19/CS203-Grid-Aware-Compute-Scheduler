package com.gacs.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.gacs.backend.dto.ApprovedScheduleDocument;
import com.gacs.backend.dto.ApprovedScheduleDocument.Approval;
import com.gacs.backend.dto.ApprovedScheduleDocument.Job;
import com.gacs.backend.dto.ApprovedScheduleDocument.RunMetadata;
import com.gacs.backend.dto.ApprovedScheduleDocument.Slot;
import com.gacs.backend.dto.ApprovedScheduleDocument.Solver;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.model.OptimizerRun;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.gacs.backend.service.ApprovedScheduleExport.HOURS_SCALE;
import static com.gacs.backend.service.ApprovedScheduleExport.PERCENT_SCALE;
import static com.gacs.backend.service.ApprovedScheduleExport.USD_SCALE;
import static com.gacs.backend.service.ApprovedScheduleExport.decimal;
import static com.gacs.backend.service.ApprovedScheduleExport.utc;

/**
 * Serializes an approved schedule to JSON for execution adapters (docs/api-contract.md section 6.6,
 * GRID-135 AC2): complete run metadata (the optimiser result and its stored run record), the approval, and every
 * job's approved time slots.
 *
 * <p>The output is deterministic: the same schedule always produces byte-identical JSON, whatever the order of
 * its items or allocations. Keys are in a fixed order, jobs are sorted by id and slots by start, numbers have a
 * fixed number of decimal places, every key is present (null when it has no value), and the text is compact with
 * no trailing newline.
 */
@Component
public class ApprovedScheduleJsonSerializer {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    /** As {@link #toDocument(OptimizeResponseDto, OptimizerRun, AuditEvent)} without a run record or approval. */
    public ApprovedScheduleDocument toDocument(OptimizeResponseDto schedule) {
        return toDocument(schedule, null, null);
    }

    /**
     * @param schedule the schedule the operator approved
     * @param run      its stored run record, for {@code run.createdAt} and {@code run.solver}; null leaves them null
     * @param approval the audit event that approved it; null leaves {@code approval} null
     * @return the export document
     * @throws IllegalArgumentException if the run id is blank, a job id is missing or repeated, a slot start is
     *                                  missing, a number is not finite, or the run record or approval belongs to
     *                                  another run or the approval is not an APPROVE
     */
    public ApprovedScheduleDocument toDocument(OptimizeResponseDto schedule, OptimizerRun run, AuditEvent approval) {
        ApprovedScheduleExport.validate(schedule);
        if (run != null && !schedule.runId().equals(run.getId())) {
            throw new IllegalArgumentException("Run record " + run.getId() + " is not for run " + schedule.runId());
        }
        if (approval != null && (approval.getActionType() != AuditActionType.APPROVE
                || !schedule.runId().equals(approval.getRunId()))) {
            throw new IllegalArgumentException("Audit event " + approval.getId() + " is not an approval of run "
                    + schedule.runId());
        }
        RunMetadata runMetadata = new RunMetadata(
                schedule.runId(),
                schedule.status(),
                schedule.provenOptimal(),
                schedule.usedSeededFallbackPrices(),
                decimal(schedule.baselineCostUsd(), USD_SCALE),
                decimal(schedule.optimizedCostUsd(), USD_SCALE),
                decimal(schedule.projectedSavingsUsd(), USD_SCALE),
                decimal(schedule.projectedSavingsPercent(), PERCENT_SCALE),
                schedule.plainEnglishExplanation(),
                run == null ? null : ApprovedScheduleExport.utc(run.getCreatedAt()),
                run == null ? null : new Solver(run.getSolverName(), run.getSolverVersion(), config(run.getSolverConfig())));
        Approval approvalRecord = approval == null ? null : new Approval(
                approval.getId(),
                approval.getActor(),
                ApprovedScheduleExport.utc(approval.getTimestamp()),
                approval.getOperatorRationale(),
                approval.getScheduleHash());
        List<Job> jobs = ApprovedScheduleExport.sortedItems(schedule).stream()
                .map(ApprovedScheduleJsonSerializer::job)
                .toList();
        return new ApprovedScheduleDocument(ApprovedScheduleDocument.FORMAT_VERSION, runMetadata, approvalRecord, jobs);
    }

    /** As {@link #serialize(OptimizeResponseDto, OptimizerRun, AuditEvent)} without a run record or approval. */
    public String serialize(OptimizeResponseDto schedule) {
        return serialize(schedule, null, null);
    }

    /**
     * @return compact JSON text of {@link #toDocument(OptimizeResponseDto, OptimizerRun, AuditEvent)}
     * @throws IllegalArgumentException as {@link #toDocument(OptimizeResponseDto, OptimizerRun, AuditEvent)}
     */
    public String serialize(OptimizeResponseDto schedule, OptimizerRun run, AuditEvent approval) {
        ApprovedScheduleDocument document = toDocument(schedule, run, approval);
        try {
            return MAPPER.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize approved schedule " + schedule.runId(), e);
        }
    }

    /** The stored solver configuration as JSON, keeping its key order; the raw text if it is not valid JSON. */
    private static JsonNode config(String solverConfig) {
        if (solverConfig == null) {
            return null;
        }
        try {
            return MAPPER.readTree(solverConfig);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(solverConfig);
        }
    }

    private static Job job(ScheduleItem item) {
        List<Slot> slots = ApprovedScheduleExport.sortedAllocations(item).stream()
                .map(ApprovedScheduleJsonSerializer::slot)
                .toList();
        return new Job(
                item.jobId(),
                item.jobName(),
                item.perJobStatus(),
                item.reason(),
                utc(item.baselineStart()),
                utc(item.scheduledStart()),
                utc(item.scheduledEnd()),
                decimal(item.allocatedCpuHours(), HOURS_SCALE),
                decimal(item.allocatedGpuHours(), HOURS_SCALE),
                decimal(item.baselineCostUsd(), USD_SCALE),
                decimal(item.optimizedCostUsd(), USD_SCALE),
                decimal(item.savingsUsd(), USD_SCALE),
                item.rescheduledFromElapsedStart(),
                slots);
    }

    private static Slot slot(Allocation allocation) {
        return new Slot(
                utc(allocation.intervalStartUtc()),
                ApprovedScheduleExport.slotEnd(allocation.intervalStartUtc()),
                decimal(allocation.cpuHours(), HOURS_SCALE),
                decimal(allocation.gpuHours(), HOURS_SCALE));
    }
}
