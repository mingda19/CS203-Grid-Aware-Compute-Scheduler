package com.gacs.backend.repository;

import com.gacs.backend.model.OptimizerRun;
import com.gacs.backend.model.OptimizerRunStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OptimizerRunRepositoryTest {

    private static final String CONFIG = "{\"cpuKwhPerHour\":0.05,\"gpuKwhPerHour\":0.4,"
            + "\"defaultMaxCpuHoursPerSlot\":256.0,\"defaultMaxGpuHoursPerSlot\":64.0}";

    @Autowired
    private OptimizerRunRepository runs;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("GRID-140 — a run persists with its id, timestamp, solver, configuration and status")
    void savesAndReloadsRun() {
        String id = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44";
        Instant createdAt = Instant.parse("2026-10-28T09:30:00Z");
        runs.saveAndFlush(OptimizerRun.builder()
                .id(id)
                .createdAt(createdAt)
                .solverName("HybridAllocationSolver")
                .solverVersion("1")
                .solverConfig(CONFIG)
                .status(OptimizerRunStatus.NEEDS_ATTENTION)
                .provenOptimal(true)
                .build());
        entityManager.clear();

        OptimizerRun reloaded = runs.findById(id).orElseThrow();

        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getSolverName()).isEqualTo("HybridAllocationSolver");
        assertThat(reloaded.getSolverVersion()).isEqualTo("1");
        assertThat(reloaded.getSolverConfig()).isEqualTo(CONFIG);
        assertThat(reloaded.getStatus()).isEqualTo(OptimizerRunStatus.NEEDS_ATTENTION);
        assertThat(reloaded.getProvenOptimal()).isTrue();
    }

    @Test
    @DisplayName("GRID-140 — a run that produced no plan stores a null provenOptimal")
    void timeoutRunHasNoOptimalityFlag() {
        String id = "11111111-2222-4333-8444-555555555555";
        runs.saveAndFlush(OptimizerRun.builder()
                .id(id)
                .createdAt(Instant.parse("2026-10-28T09:31:00Z"))
                .solverName("HybridAllocationSolver")
                .solverVersion("1")
                .solverConfig(CONFIG)
                .status(OptimizerRunStatus.TIMEOUT)
                .build());
        entityManager.clear();

        OptimizerRun reloaded = runs.findById(id).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(OptimizerRunStatus.TIMEOUT);
        assertThat(reloaded.getProvenOptimal()).isNull();
    }
}
