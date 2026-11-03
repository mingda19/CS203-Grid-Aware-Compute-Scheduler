package com.gacs.backend.repository;

import com.gacs.backend.model.ScheduleRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ScheduleRunRepository extends JpaRepository<ScheduleRun, Long> {

    Optional<ScheduleRun> findByRunId(String runId);
}
