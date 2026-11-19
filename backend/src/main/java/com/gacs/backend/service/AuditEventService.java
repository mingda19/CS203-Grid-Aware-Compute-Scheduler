package com.gacs.backend.service;

import com.gacs.backend.dto.AuditEventDetailDto;
import com.gacs.backend.dto.AuditEventDetailDto.RunSnapshot;
import com.gacs.backend.dto.AuditEventDetailDto.ScheduleAvailability;
import com.gacs.backend.dto.AuditEventFilter;
import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ScheduleRun;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.AuditEventRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import com.gacs.backend.repository.ScheduleRunRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.util.ScheduleHashUtil;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service managing audit event recording and querying.
 * Strictly enforces AC4.1 and AC4.1a (server-side authenticated actor extraction).
 */
@Service
public class AuditEventService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "timestamp");

    private final AuditEventRepository auditEventRepository;

    private final OptimizerRunRepository optimizerRunRepository;

    private final OptimizationPlanRepository planRepository;

    private final UserRepository userRepository;

    private final ScheduleRunRepository scheduleRunRepository;

    private final StoredScheduleReader scheduleReader;

    private final Clock clock;

    public AuditEventService(AuditEventRepository auditEventRepository,
                             OptimizerRunRepository optimizerRunRepository,
                             OptimizationPlanRepository planRepository,
                             UserRepository userRepository,
                             ScheduleRunRepository scheduleRunRepository,
                             StoredScheduleReader scheduleReader,
                             Clock clock) {
        this.auditEventRepository = auditEventRepository;
        this.optimizerRunRepository = optimizerRunRepository;
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.scheduleRunRepository = scheduleRunRepository;
        this.scheduleReader = scheduleReader;
        this.clock = clock;
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
                .timestamp(Instant.now(clock))
                .runId(request.runId().trim())
                .scheduleHash(request.scheduleHash())
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

    /**
     * True when {@code event} is the viewer's to see: they performed it, or they own the run it
     * concerns. Both are checked because ownership and authorship are separate facts, even though in
     * today's flows only a run's owner can decide on it.
     */
    private boolean isViewersOwnEvent(AuditEventResponseDto event, Long id, String viewerEmail) {
        if (viewerEmail == null) {
            return false;
        }
        String viewer = viewerEmail.trim().toLowerCase(Locale.ROOT);
        if (event.actor() != null && event.actor().trim().toLowerCase(Locale.ROOT).equals(viewer)) {
            return true;
        }
        return planRepository.findById(event.runId())
                .map(plan -> isOwnedByViewer(plan, viewerEmail))
                .orElse(false);
    }

    /**
     * {@link #getEventsByRunId} restricted to {@code actorEmail}'s own events on that run. An empty
     * list is returned for a run the caller never acted on, which is also what a nonexistent run
     * gives - deliberately, so the endpoint does not confirm that someone else's run exists.
     */
    @Transactional(readOnly = true)
    public List<AuditEventResponseDto> getEventsByRunIdForActor(String runId, String actorEmail) {
        String wanted = actorEmail == null ? "" : actorEmail.trim().toLowerCase(Locale.ROOT);
        // The owner of the run also sees the events of the people who decided it.
        boolean owner = planRepository.findById(runId).map(plan -> isOwnedByViewer(plan, actorEmail)).orElse(false);
        return auditEventRepository.findByRunIdOrderByTimestampDesc(runId).stream()
                .filter(event -> owner || (event.getActor() != null
                        && event.getActor().trim().toLowerCase(Locale.ROOT).equals(wanted)))
                .map(AuditEventResponseDto::fromEntity)
                .toList();
    }

    /**
     * Queries the audit log by date range, operator, action and run (GRID-134 AC6.1a).
     * Results are newest first unless the caller asks for another sort.
     *
     * @throws IllegalArgumentException if {@code from} is not before {@code to}
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponseDto> getEvents(AuditEventFilter filter, Pageable pageable) {
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        Pageable sorted = pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
        return auditEventRepository.findAll(matching(filter), sorted)
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
     * Returns an audit event with the optimiser run and stored schedule it refers to (GRID-134 AC6.1b).
     * The run is null when no run is stored under the event's runId. The schedule itself goes only to the run's
     * owner, as for decisions and exports; anyone may see whether it still matches the hash on the event.
     *
     * @param viewerEmail the authenticated caller
     */
    @Transactional(readOnly = true)
    public AuditEventDetailDto getEventDetail(Long id, String viewerEmail) {
        AuditEventResponseDto event = getEventById(id);
        // Withholding only the schedule body was not enough: the event itself carries the actor, the
        // action, the run id, the hash and the rationale, and ids are sequential, so anyone could walk
        // /api/audit-events/{id} and read every operator's decisions. An event that is not the
        // viewer's own is reported as absent, which also avoids confirming that it exists.
        if (!isViewersOwnEvent(event, id, viewerEmail)) {
            throw new ResourceNotFoundException("Audit event not found with id: " + id);
        }
        ScheduleRun scheduleRun = scheduleRunRepository.findByRunId(event.runId()).orElse(null);
        RunSnapshot run = optimizerRunRepository.findById(event.runId())
                .map(optimizerRun -> RunSnapshot.fromEntity(optimizerRun, scheduleRun))
                .orElse(null);
        OptimizationPlan plan = planRepository.findById(event.runId()).orElse(null);
        if (plan == null) {
            return new AuditEventDetailDto(event, run, ScheduleAvailability.NOT_STORED, null, null);
        }
        boolean hashMatches = ScheduleHashUtil.computeSha256(plan.getResultJson()).equals(event.scheduleHash());
        // The owner sees the schedule, and so does anyone who decided it.
        if (!isOwnedByViewer(plan, viewerEmail) && !hasDecided(plan, viewerEmail)) {
            return new AuditEventDetailDto(event, run, ScheduleAvailability.OWNER_ONLY, hashMatches, null);
        }
        return new AuditEventDetailDto(event, run, ScheduleAvailability.VISIBLE, hashMatches,
                scheduleReader.read(plan.getResultJson()).orElse(null));
    }

    private boolean hasDecided(OptimizationPlan plan, String viewerEmail) {
        return viewerEmail != null && auditEventRepository.existsByRunIdAndActorIgnoreCaseAndActionTypeIn(
                plan.getRunId(), viewerEmail.trim(), ScheduleGate.DECISIONS);
    }

    private boolean isOwnedByViewer(OptimizationPlan plan, String viewerEmail) {
        return viewerEmail != null && userRepository.findByEmail(viewerEmail)
                .map(User::getId)
                .filter(userId -> userId.equals(plan.getOwner().getId()))
                .isPresent();
    }

    static Specification<AuditEvent> matching(AuditEventFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("timestamp"), filter.to()));
            }
            if (filter.actionType() != null) {
                predicates.add(cb.equal(root.get("actionType"), filter.actionType()));
            }
            if (filter.onlyActor() != null && !filter.onlyActor().isBlank()) {
                // Exact match, not a substring: see AuditEventFilter#onlyActor for why that matters.
                // The viewer's own events, and every event on a run they own.
                String viewer = filter.onlyActor().trim().toLowerCase(Locale.ROOT);
                Subquery<String> ownedRuns = query.subquery(String.class);
                Root<OptimizationPlan> ownedPlan = ownedRuns.from(OptimizationPlan.class);
                ownedRuns.select(ownedPlan.get("runId"))
                        .where(cb.equal(cb.lower(ownedPlan.get("owner").<String>get("email")), viewer));
                predicates.add(cb.or(cb.equal(cb.lower(root.<String>get("actor")), viewer),
                        root.get("runId").in(ownedRuns)));
            }
            if (filter.actor() != null && !filter.actor().isBlank()) {
                predicates.add(cb.like(cb.lower(root.<String>get("actor")), containsPattern(filter.actor()), '\\'));
            }
            if (filter.runId() != null && !filter.runId().isBlank()) {
                predicates.add(cb.like(cb.lower(root.<String>get("runId")), containsPattern(filter.runId()), '\\'));
            }
            if (filter.jobId() != null) {
                Subquery<String> runsWithJob = query.subquery(String.class);
                Root<OptimizationPlan> plan = runsWithJob.from(OptimizationPlan.class);
                Join<OptimizationPlan, Long> job = plan.join("jobIds");
                runsWithJob.select(plan.get("runId")).where(cb.equal(job, filter.jobId()));
                predicates.add(root.get("runId").in(runsWithJob));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Lower-cased {@code %term%} with LIKE wildcards in the term escaped. */
    private static String containsPattern(String term) {
        String escaped = term.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
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
            throw new AccessDeniedException(
                    "Authenticated principal has no usable name: cannot record audit event without verified identity");
        }
        return principalName.trim();
    }
}
