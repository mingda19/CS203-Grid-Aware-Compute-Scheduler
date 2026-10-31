package com.gacs.backend.repository;

import com.gacs.backend.model.ScheduleExplanation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScheduleExplanationRepository extends JpaRepository<ScheduleExplanation, String> {
    Optional<ScheduleExplanation> findByRunIdAndOwnerEmail(String runId, String ownerEmail);
}
