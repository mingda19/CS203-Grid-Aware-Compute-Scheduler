package com.gacs.backend.repository;

import com.gacs.backend.model.WorkloadSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkloadScheduleRepository extends JpaRepository<WorkloadSchedule, Long> {
    List<WorkloadSchedule> findByRunIdOrderByIdAsc(String runId);
    List<WorkloadSchedule> findByJobIdIn(Collection<Long> jobIds);
    List<WorkloadSchedule> findByOwnerEmailOrderByStartUtcAsc(String ownerEmail);
    Optional<WorkloadSchedule> findByIdAndOwnerEmail(Long id, String ownerEmail);

    /**
     * Retires every calendar entry for one of these jobs that belongs to some OTHER run (bug 4):
     * when a newly approved run is applied, any entry an earlier applied run wrote for the same
     * job is superseded rather than left to accumulate alongside the new one. Entries with
     * {@code status} (Completed) stay: they record work that already ran.
     */
    void deleteByOwnerEmailAndJobIdInAndRunIdNotAndStatusNot(String ownerEmail, Collection<Long> jobIds,
                                                             String runId, String status);

    /** Removes a run's calendar entries except those with {@code status}, which record work that already ran. */
    void deleteByRunIdAndStatusNot(String runId, String status);

    /** Removes a deleted job's calendar entries except those with {@code status}, which record work that already ran. */
    void deleteByJobIdAndStatusNot(Long jobId, String status);
}
