package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleItem;
import com.gacs.backend.model.ScheduleRun;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates persisting one optimisation run around the slow, external part - the LLM call -
 * without ever holding a database connection across it (docs/schedule-explanation-design.md
 * sections 1, 2.7, 4, 5; see the security review this responds to for the original report).
 *
 * <p>Deliberately holds no {@code @Transactional} of its own and calls {@link ScheduleRunPersistence}
 * twice, not once: {@link ScheduleRunPersistence#persist} (a short transaction - writes the run,
 * items and constraint impacts with deterministic text already filled in, then releases its
 * connection) happens-before {@link ExplanationGenerator#generate} (no transaction at all - this is
 * the HTTP call to the LLM provider, bounded only by its own explicit timeout), which
 * happens-before {@link ScheduleRunPersistence#finalizeExplanation} (a second short transaction -
 * writes the one explanation row, and only on LLM success, overwrites the deterministic text with
 * the LLM's). {@code ScheduleOptimizationService.optimize()} must not be {@code @Transactional}
 * either, or Spring's REQUIRED propagation would fold both of these back into one transaction that
 * still spans the HTTP call - which is exactly the problem this split exists to avoid.
 */
@Service
public class ScheduleRunRecorder {

    private final ScheduleRunPersistence persistence;
    private final ExplanationGenerator explanationGenerator;

    public ScheduleRunRecorder(ScheduleRunPersistence persistence, ExplanationGenerator explanationGenerator) {
        this.persistence = persistence;
        this.explanationGenerator = explanationGenerator;
    }

    public void record(AllocationResult result, List<PlacementConstraint> constraints,
                       List<SynchronizedFinishConstraint> synchronizedGroups, OptimizeResponseDto response,
                       String ownerEmail) {
        record(result, constraints, synchronizedGroups, response, ownerEmail, ignored -> { });
    }

    /**
     * Same as {@link #record(AllocationResult, List, List, OptimizeResponseDto, String)}, plus a
     * hook run inside the same transaction as {@link ScheduleRunPersistence#finalizeExplanation} -
     * see that method's javadoc for why a caller would want this instead of just doing its own
     * persistence after {@code record} returns.
     */
    public void record(AllocationResult result, List<PlacementConstraint> constraints,
                       List<SynchronizedFinishConstraint> synchronizedGroups, OptimizeResponseDto response,
                       String ownerEmail, Consumer<OptimizeResponseDto> afterExplanationPersisted) {
        ScheduleRunPersistence.Persisted persisted = persistence.persist(result, constraints, synchronizedGroups,
                response, ownerEmail);

        ScheduleRun run = persisted.run();
        List<ScheduleItem> items = persisted.items();
        List<ConstraintImpact> impacts = persisted.impacts();
        ExplanationOutcome explanation = explanationGenerator.generate(run, items, impacts);

        persistence.finalizeExplanation(run.getId(), explanation, response, afterExplanationPersisted);
    }
}
