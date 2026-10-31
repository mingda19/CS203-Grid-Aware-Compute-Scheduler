package com.gacs.backend.repository;

import com.gacs.backend.model.ScheduleExplanation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ScheduleExplanationRepository extends JpaRepository<ScheduleExplanation, Long> {

    Optional<ScheduleExplanation> findByScheduleRunId(Long scheduleRunId);
}
