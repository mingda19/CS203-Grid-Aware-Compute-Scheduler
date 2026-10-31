package com.gacs.backend.repository;

import com.gacs.backend.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for AuditEvent entities.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByRunIdOrderByTimestampDesc(String runId);

    List<AuditEvent> findByActorOrderByTimestampDesc(String actor);

    Page<AuditEvent> findAllByOrderByTimestampDesc(Pageable pageable);

    List<AuditEvent> findAllByOrderByTimestampDesc();
}
