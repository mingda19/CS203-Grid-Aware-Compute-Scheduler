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
    // The review queue: every owner's plans, newest first. "Not" leaves out one owner's plans.
    Page<OptimizationPlan> findAllByOrderByCreatedAtDescRunIdDesc(Pageable pageable);

    Page<OptimizationPlan> findByReviewStateOrderByCreatedAtDescRunIdDesc(ReviewState state, Pageable pageable);

    Page<OptimizationPlan> findByOwnerIdNotOrderByCreatedAtDescRunIdDesc(Long ownerId, Pageable pageable);

    Page<OptimizationPlan> findByOwnerIdNotAndReviewStateOrderByCreatedAtDescRunIdDesc(
        Long ownerId, ReviewState state, Pageable pageable);

    /** The owner's email without loading the plan, so a later locked read of it is not served from a stale cache. */
    @Query("select p.owner.email from OptimizationPlan p where p.runId = :runId")
    Optional<String> findOwnerEmail(@Param("runId") String runId);

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

    /** One job in one plan, without loading the plan's stored result. */
    record PlanLink(Long jobId, String runId, ReviewState state, Instant createdAt, Instant invalidatedAt) {}

    @Query("select new com.gacs.backend.repository.OptimizationPlanRepository$PlanLink(j, p.runId, p.reviewState,"
        + " p.createdAt, p.invalidatedAt) from OptimizationPlan p join p.jobIds j where j in :ids")
    List<PlanLink> findLinks(@Param("ids") java.util.Collection<Long> ids);

    /** The (job, run) pairs where the job left the plan after approval. */
    @Query("select r, p.runId from OptimizationPlan p join p.removedJobIds r where r in :ids")
    List<Object[]> findRemoved(@Param("ids") java.util.Collection<Long> ids);

    @Query("select p.inputSnapshot from OptimizationPlan p where p.runId = :runId")
    Optional<String> findInputSnapshot(@Param("runId") String runId);

    /** Of these jobs, the ones in an approved plan that they have not left. */
    @Query("select j from OptimizationPlan p join p.jobIds j where p.reviewState = com.gacs.backend.model.ReviewState.APPROVED"
        + " and j in :ids and j not member of p.removedJobIds")
    List<Long> findApprovedLiveJobIds(@Param("ids") java.util.Collection<Long> ids);
    List<OptimizationPlan> findByReviewState(ReviewState state);

    /** Plans with no recorded job IDs: stored before they were kept, or with no jobs at all. */
    @Query("select p from OptimizationPlan p where p.jobIds is empty")
    List<OptimizationPlan> findWithoutJobIds();
}
