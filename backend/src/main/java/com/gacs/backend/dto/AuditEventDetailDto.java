package com.gacs.backend.dto;

import com.gacs.backend.model.OptimizerRun;
import com.gacs.backend.model.OptimizerRunStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Audit event together with the optimiser run and stored schedule it refers to (GRID-134 AC6.1b).
 * The schedule itself is included only for the run's owner.
 */
@Schema(description = "Audit event with the optimiser run and stored schedule the decision was made on")
public record AuditEventDetailDto(
    @Schema(description = "The audit event record") AuditEventResponseDto event,
    @Schema(description = "Optimiser run snapshot; null when no run is stored for the event's runId") RunSnapshot run,
    @Schema(description = "VISIBLE when the caller owns the run, OWNER_ONLY when a schedule is stored but belongs to "
        + "someone else, NOT_STORED when no schedule is stored for the event's runId") ScheduleAvailability scheduleAvailability,
    @Schema(description = "Whether the stored schedule still hashes to the event's scheduleHash; null when NOT_STORED")
    Boolean scheduleHashMatches,
    @Schema(description = "The stored optimiser result (section 6.4 response shape); only when VISIBLE")
    OptimizeResponseDto schedule
) {

    public enum ScheduleAvailability { VISIBLE, OWNER_ONLY, NOT_STORED }

    @Schema(description = "Solver and configuration that produced the schedule the decision was made on")
    public record RunSnapshot(
        @Schema(description = "Optimiser run ID") String id,
        @Schema(description = "When the run was created in UTC") Instant createdAt,
        @Schema(description = "Solver name", example = "OR-Tools CP-SAT") String solverName,
        @Schema(description = "Solver version", example = "9.10") String solverVersion,
        @Schema(description = "JSON snapshot of the solver configuration the run used") String solverConfig,
        @Schema(description = "How the run ended") OptimizerRunStatus status,
        @Schema(description = "Whether the solver proved the plan optimal; null when no plan was produced") Boolean provenOptimal
    ) {
        public static RunSnapshot fromEntity(OptimizerRun run) {
            return new RunSnapshot(
                run.getId(),
                run.getCreatedAt(),
                run.getSolverName(),
                run.getSolverVersion(),
                run.getSolverConfig(),
                run.getStatus(),
                run.getProvenOptimal()
            );
        }
    }
}
