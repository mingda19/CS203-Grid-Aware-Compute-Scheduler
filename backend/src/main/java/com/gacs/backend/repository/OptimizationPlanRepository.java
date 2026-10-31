package com.gacs.backend.repository;

import com.gacs.backend.model.OptimizationPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OptimizationPlanRepository extends JpaRepository<OptimizationPlan, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from OptimizationPlan p where p.runId = :runId and p.owner.id = :ownerId")
    Optional<OptimizationPlan> findOwnedForUpdate(@Param("runId") String runId, @Param("ownerId") Long ownerId);
}
