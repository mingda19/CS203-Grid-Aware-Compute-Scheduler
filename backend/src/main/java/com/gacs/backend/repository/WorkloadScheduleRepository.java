package com.gacs.backend.repository;

import com.gacs.backend.model.WorkloadSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkloadScheduleRepository extends JpaRepository<WorkloadSchedule, Long> {
    List<WorkloadSchedule> findByOwnerEmailOrderByStartUtcAsc(String ownerEmail);
    Optional<WorkloadSchedule> findByIdAndOwnerEmail(Long id, String ownerEmail);
}
