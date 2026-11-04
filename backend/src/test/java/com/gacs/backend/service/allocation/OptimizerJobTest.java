package com.gacs.backend.service.allocation;

import com.gacs.backend.model.JobPriority;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug 2: {@link OptimizerJob#identity()} must carry both the job's id and its name, so an error
 * message built from it lets an operator tell their job apart from any other with the same id or
 * the same (possibly duplicated) name.
 */
class OptimizerJobTest {
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void identityCarriesBothIdAndName() {
        OptimizerJob job = new OptimizerJob(23L, "gpt-agent-1", 1.0, 0.0, T0, T0.plusHours(1), 0, false, JobPriority.NORMAL);
        assertThat(job.identity()).isEqualTo("id 23 ('gpt-agent-1')");
        // label() is unchanged: its exact format is pinned by docs/api-contract.md and PriorityConflictTest.
        assertThat(job.label()).isEqualTo("id 23");
    }

    @Test
    void identityFallsBackToTheQuotedNameWhenThereIsNoId() {
        OptimizerJob job = new OptimizerJob(null, "unsaved-job", 1.0, 0.0, T0, T0.plusHours(1), 0, false, JobPriority.NORMAL);
        assertThat(job.identity()).isEqualTo("'unsaved-job'");
        assertThat(job.identity()).isEqualTo(job.label());
    }
}
