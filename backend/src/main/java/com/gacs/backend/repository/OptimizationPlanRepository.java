package com.gacs.backend.repository;

import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OptimizationPlanRepository extends JpaRepository<OptimizationPlan, String> {
    Page<OptimizationPlan> findByOwnerIdOrderByCreatedAtDescRunIdDesc(
        Long ownerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from OptimizationPlan p where p.runId = :runId and p.owner.id = :ownerId")
    Optional<OptimizationPlan> findOwnedForUpdate(@Param("runId") String runId, @Param("ownerId") Long ownerId);

    /** Read-only lookup for exports; no lock, since exporting does not change the plan. */
    @Query("select p from OptimizationPlan p where p.runId = :runId and p.owner.id = :ownerId")
    Optional<OptimizationPlan> findOwned(@Param("runId") String runId, @Param("ownerId") Long ownerId);

    /** The owner's plans in a review state that were created after the given time, newest first. */
    @Query("select p from OptimizationPlan p where p.owner.id = :ownerId and p.reviewState = :state"
        + " and p.createdAt > :after order by p.createdAt desc")
    List<OptimizationPlan> findOwnedInStateCreatedAfter(@Param("ownerId") Long ownerId,
                                                        @Param("state") ReviewState state,
                                                        @Param("after") Instant after);

    List<OptimizationPlan> findByOwnerIdAndReviewState(Long ownerId, ReviewState state);
    List<OptimizationPlan> findByReviewState(ReviewState state);

    /** Plans with no recorded job IDs: stored before they were kept, or with no jobs at all. */
    @Query("select p from OptimizationPlan p where p.jobIds is empty")
    List<OptimizationPlan> findWithoutJobIds();
}
