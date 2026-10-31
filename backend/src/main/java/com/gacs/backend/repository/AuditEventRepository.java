package com.gacs.backend.repository;

import com.gacs.backend.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Append-only repository for AuditEvent entities (GRID-84 / AC5.1).
 * Deliberately extends the bare Repository marker, not JpaRepository, so no delete*
 * methods exist on this type. The database also rejects UPDATE/DELETE/TRUNCATE
 * (see db/migration/V3__audit_events_append_only.sql).
 */
@org.springframework.stereotype.Repository
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    <S extends AuditEvent> S save(S event);

    <S extends AuditEvent> S saveAndFlush(S event);

    <S extends AuditEvent> List<S> saveAll(Iterable<S> events);

    void flush();

    Optional<AuditEvent> findById(Long id);

    List<AuditEvent> findByRunIdOrderByTimestampDesc(String runId);

    List<AuditEvent> findByActorOrderByTimestampDesc(String actor);

    Page<AuditEvent> findAllByOrderByTimestampDesc(Pageable pageable);

    List<AuditEvent> findAllByOrderByTimestampDesc();
}
