package com.gacs.backend.service;

import com.gacs.backend.service.ScheduleSolver.JobSpec;
import com.gacs.backend.service.ScheduleSolver.SolverInput;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates every slot a flexible job could legally occupy, independent of capacity and
 * of any other job - the feasibility test every {@link ScheduleSolver} implementation and
 * the brute-force test oracle must agree on.
 *
 * <p>A slot qualifies when it is priced, inside the optimization horizon, inside the job's
 * own flexibility window, and finishes by the job's deadline. Capacity is deliberately not
 * checked here: capacity feasibility depends on which other jobs share a slot, which is the
 * solver's job to resolve, not this helper's.
 */
public final class CandidateSlots {

    private CandidateSlots() {
    }

    /**
     * Candidate slots for {@code job}, in ascending time order (callers rely on this order
     * for deterministic tie-breaking toward the earliest slot).
     */
    public static List<LocalDateTime> forJob(JobSpec job, SolverInput input) {
        LocalDateTime baseline = job.baselineSlot();
        List<LocalDateTime> slots = new ArrayList<>();
        for (int offset = 0; offset <= job.flexibilityWindowHours(); offset++) {
            LocalDateTime slot = baseline.plusHours(offset);
            if (slot.isBefore(input.horizonStart()) || !slot.isBefore(input.horizonEnd())) {
                continue;
            }
            // A one-hour job starting at `slot` ends at slot+1h; finishing exactly on the
            // deadline is allowed, finishing after it is not.
            if (slot.plusHours(1).isAfter(job.deadline())) {
                continue;
            }
            if (input.prices().priceAt(slot).isEmpty()) {
                continue;
            }
            slots.add(slot);
        }
        return slots;
    }
}
