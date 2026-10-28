package com.gacs.backend.service;

import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.repository.AuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Service managing audit event recording and querying.
 * Strictly enforces AC4.1 and AC4.1a (server-side authenticated actor extraction).
 */
@Service
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;

    public AuditEventService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * Records an audit event for an operator action.
     * Enforces AC4.1a: Actor identity must be extracted server-side strictly
     * from Spring Security's authenticated principal
     * (SecurityContextHolder.getContext().getAuthentication().getName()).
     * Caller-supplied actor identity from client payloads is never accepted.
     *
     * @param request the validated audit event request
     * @return the persisted audit event response DTO
     */
    @Transactional
    public AuditEventResponseDto recordEvent(AuditEventRequestDto request) {
        String actor = extractAuthenticatedActor();

        AuditEvent event = AuditEvent.builder()
                .actor(actor)
                .actionType(request.actionType())
                .timestamp(Instant.now())
                .runId(request.runId().trim())
                .scheduleHash(request.scheduleHash().trim())
                .operatorRationale(request.operatorRationale().trim())
                .build();

        AuditEvent saved = auditEventRepository.save(event);
        return AuditEventResponseDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponseDto> getEventsByRunId(String runId) {
        return auditEventRepository.findByRunIdOrderByTimestampDesc(runId).stream()
                .map(AuditEventResponseDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponseDto> getEvents(Pageable pageable) {
        return auditEventRepository.findAllByOrderByTimestampDesc(pageable)
                .map(AuditEventResponseDto::fromEntity);
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponseDto> getAllEvents() {
        return auditEventRepository.findAllByOrderByTimestampDesc().stream()
                .map(AuditEventResponseDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public AuditEventResponseDto getEventById(Long id) {
        AuditEvent event = auditEventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Audit event not found with id: " + id));
        return AuditEventResponseDto.fromEntity(event);
    }

    /**
     * Extracts the authenticated actor identity server-side strictly from
     * Spring Security's SecurityContextHolder.
     */
    String extractAuthenticatedActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            throw new AccessDeniedException("Unauthenticated caller: cannot record audit event without verified identity");
        }
        String principalName = auth.getName();
        if (principalName == null || principalName.trim().isEmpty()) {
            throw new AccessDeniedException("Principal name is empty in security context");
        }
        return principalName.trim();
    }
}
