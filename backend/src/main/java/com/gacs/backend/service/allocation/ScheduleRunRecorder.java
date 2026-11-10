package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.ExplanationResponseDto;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * Persists one optimisation run: the run, its items, its constraint impacts and its explanation.
 *
 * <p>This used to split the work across two transactions with an LLM call in the gap, so that no
 * database connection was held across a provider round trip. The explanation is now generated
 * deterministically from the run's own numbers before persistence begins, so there is no external
 * call left to keep out of a transaction. The two phases remain only because
 * {@code afterExplanationPersisted} must run in the same transaction as the explanation row - see
 * {@link ScheduleRunPersistence#finalizeExplanation}.
 */
@Service
public class ScheduleRunRecorder {

    private final ScheduleRunPersistence persistence;

    public ScheduleRunRecorder(ScheduleRunPersistence persistence) {
        this.persistence = persistence;
    }

    public List<ExplanationResponseDto.JobExplanation> jobExplanations(Long runId) {
        return persistence.jobExplanations(runId);
    }

    /**
     * {@code afterExplanationPersisted} runs inside the same transaction as {@link
     * ScheduleRunPersistence#finalizeExplanation} - see that method's javadoc for why a caller
     * would want this instead of just doing its own persistence after {@code record} returns. A
     * caller with nothing to do there passes a no-op.
     */
    public void record(AllocationResult result, List<PlacementConstraint> constraints,
                       List<SynchronizedFinishConstraint> synchronizedGroups, OptimizeResponseDto response,
                       String ownerEmail, Consumer<OptimizeResponseDto> afterExplanationPersisted) {
        ScheduleRunPersistence.Persisted persisted = persistence.persist(result, constraints, synchronizedGroups,
                response, ownerEmail);

        persistence.finalizeExplanation(persisted.run().getId(), response, afterExplanationPersisted);
    }
}
