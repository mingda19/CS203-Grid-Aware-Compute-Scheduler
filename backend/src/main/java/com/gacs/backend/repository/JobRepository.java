package com.gacs.backend.repository;

import com.gacs.backend.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    /**
     * Finds all jobs owned by an operator ordered chronologically by original start time.
     */
    List<Job> findByOwnerEmailOrderByOriginalStartTimeAsc(String ownerEmail);

    /**
     * Finds a single job by its ID scoped to the authenticated owner.
     */
    Optional<Job> findByIdAndOwnerEmail(Long id, String ownerEmail);

    /**
     * Finds all jobs owned by an operator filtered by their exemption status.
     */
    List<Job> findByOwnerEmailAndIsExempt(String ownerEmail, boolean isExempt);

    /**
     * Checks if a job exists and belongs to the given operator.
     */
    boolean existsByIdAndOwnerEmail(Long id, String ownerEmail);
}
