package com.gacs.backend.repository;

import com.gacs.backend.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The one method of {@link org.springframework.data.jpa.repository.JpaSpecificationExecutor} that
 * {@code AuditEventService.getEvents} needs, declared on its own so {@link AuditEventRepository}
 * does not have to extend the real {@code JpaSpecificationExecutor} to get it - that interface also
 * declares {@code delete(Specification)}, which would reintroduce a delete method onto this
 * deliberately append-only repository (see {@link AuditEventRepository}'s javadoc and
 * {@code AuditEventRepositoryTest.testRepositoryHasNoDeleteMethods}). Spring Data still backs this
 * with {@code SimpleJpaRepository}, which already implements the real interface, so this needs no
 * implementation class of its own - the signature match is enough.
 */
public interface AuditEventSpecificationExecutor {

    Page<AuditEvent> findAll(Specification<AuditEvent> spec, Pageable pageable);
}
