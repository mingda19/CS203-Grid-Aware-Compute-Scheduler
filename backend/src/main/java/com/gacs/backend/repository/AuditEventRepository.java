package com.gacs.backend.repository;

import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for AuditEvent entities.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findByRunIdOrderByTimestampDesc(String runId);

    List<AuditEvent> findByActorOrderByTimestampDesc(String actor);

    Page<AuditEvent> findAllByOrderByTimestampDesc(Pageable pageable);

    List<AuditEvent> findAllByOrderByTimestampDesc();

    /** The most recently recorded event of the given types for a run; IDs increase with insertion order. */
    Optional<AuditEvent> findFirstByRunIdAndActionTypeInOrderByIdDesc(String runId, Collection<AuditActionType> types);
}
