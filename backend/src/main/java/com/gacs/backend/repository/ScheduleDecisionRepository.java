package com.gacs.backend.repository;

import com.gacs.backend.model.ScheduleDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScheduleDecisionRepository extends JpaRepository<ScheduleDecision, Long> {
    List<ScheduleDecision> findByPlanRunIdOrderByIdAsc(UUID runId);
}
