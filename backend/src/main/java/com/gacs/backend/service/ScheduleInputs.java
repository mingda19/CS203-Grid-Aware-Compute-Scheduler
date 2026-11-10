package com.gacs.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.exception.ConflictException;
import com.gacs.backend.model.*;
import com.gacs.backend.service.allocation.CapacityLimit;
import com.gacs.backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.BadCredentialsException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** Captures provenance before solving, without holding a transaction across solver/LLM calls. */
@Service
public class ScheduleInputs {
    private final UserRepository users;
    private final JobRepository jobs;
    private final FacilityRepository facilities;
    private final OptimizationPlanRepository plans;
    private final Clock clock;
    private final com.gacs.backend.service.allocation.OptimizerProperties properties;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public ScheduleInputs(UserRepository users, JobRepository jobs, FacilityRepository facilities,
                          OptimizationPlanRepository plans, Clock clock, com.gacs.backend.service.allocation.OptimizerProperties properties) {
        this.users = users;
        this.jobs = jobs;
        this.facilities = facilities;
        this.plans = plans;
        this.clock = clock;
        this.properties = properties;
    }

    public User lock(String email) {
        return users.lockByEmail(email).orElseThrow(() -> new BadCredentialsException("Authenticated operator not found"));
    }

    public record JobState(Double cpu, Double gpu, java.time.LocalDateTime start,
        java.time.LocalDateTime deadline, Integer flexibility, boolean exempt, JobPriority priority,
        boolean cancelled, String constraints, ConfirmationOutcome confirmation) {}
    public record JobVersion(long revision, JobState state) {}
    public record FacilityState(long revision, Double cpu, Double gpu, String location) {}
    /**
     * @param reserved what approved plans used when the run was solved, by hour. Absent in a snapshot taken
     *                 before the ledger existed, which means nothing was reserved.
     */
    public record Snapshot(OptimizeRequestDto request, Map<Long, JobVersion> jobs, FacilityState facility,
                           String refusal, Map<LocalDateTime, CapacityLimit> reserved) {
        public Map<LocalDateTime, CapacityLimit> reservedOrEmpty() { return reserved == null ? Map.of() : reserved; }
    }

    public static JobState state(Job j) {
        return new JobState(j.getCpuHours(), j.getGpuHours(), j.getOriginalStartTime(), j.getDeadline(),
            j.getFlexibilityWindowHours(), j.isExempt(), j.getPriority(), "CANCELLED".equals(j.getStatus()),
            j.getRawConstraints(), j.getConfirmationOutcome());
    }

    public FacilityState facility() {
        return facilities.findFirstByOrderByIdAsc()
            .map(f -> new FacilityState(f.getSchedulingRevision(), f.getMaxCpuHours(), f.getMaxGpuHours(), f.getLocation()))
            .orElse(new FacilityState(0, 256.0, 64.0, "LZ_NORTH"));
    }

    /**
     * Locks the shared facility row, creating the row first if there is none. Call it after the owner's row,
     * never before: an approval takes the owner and then the facility, and a facility change takes the
     * facility only.
     */
    public void lockFacility(String creatorEmail) {
        if (facilities.findFirstByOrderByIdAsc().isEmpty())
            facilities.save(Facility.builder().ownerEmail(creatorEmail).build());
        facilities.lockShared();
    }

    /**
     * @param reserved what approved plans use now, by hour. It is stored so the gate can tell later whether
     *                 capacity was freed or taken.
     * @throws ConflictException a job in the run is already in an approved plan. Planning it again would
     *                           end that approval for the plan's other jobs.
     */
    @Transactional
    public String capture(OptimizeRequestDto request, String email, Map<LocalDateTime, CapacityLimit> reserved) {
        lock(email);
        if (request.jobs() != null) {
            var ids = request.jobs().stream().filter(Objects::nonNull).map(OptimizeRequestDto.JobInput::id)
                .filter(Objects::nonNull).toList();
            if (!ids.isEmpty()) {
                var approved = plans.findApprovedLiveJobIds(ids);
                if (!approved.isEmpty())
                    throw new ConflictException("Job " + approved.get(0) + " is already approved. Delete it or revoke its plan first.");
            }
        }
        Map<Long, JobVersion> versions = new TreeMap<>();
        String refusal = null;
        if (request.jobs() != null) for (var input : request.jobs()) {
            if (input == null || input.id() == null) continue; // Request validation belongs to the optimizer.
            Job j = jobs.findByIdAndOwnerEmail(input.id(), email).orElse(null);
            if (j == null) { refusal = "job " + input.id() + " is no longer in your jobs"; continue; }
            versions.put(j.getId(), new JobVersion(j.getSchedulingRevision(), state(j)));
            if (!matches(input, j)) refusal = "Submitted scheduling inputs do not match job " + j.getId();
        }
        FacilityState f = facility();
        if (request.clusterCapacity() == null &&
            (Double.compare(properties.defaultMaxCpuHoursPerSlot(), f.cpu()) != 0 ||
             Double.compare(properties.defaultMaxGpuHoursPerSlot(), f.gpu()) != 0))
            refusal = "Default solver capacity does not match the facility; submit its current capacity";
        if (request.clusterCapacity() != null &&
            (!Objects.equals(request.clusterCapacity().maxConcurrentCpuHours(), f.cpu()) ||
             !Objects.equals(request.clusterCapacity().maxConcurrentGpuHours(), f.gpu())))
            refusal = "Submitted capacity does not match the facility";
        if (request.forecast() == null && request.forecastLocation() != null &&
            !request.forecastLocation().equals(f.location())) refusal = "Submitted location does not match the facility";
        if (request.capacityOverrides() != null) for (var c : request.capacityOverrides()) {
            if (c != null && ((c.maxCpuHours() != null && c.maxCpuHours() > f.cpu()) ||
                (c.maxGpuHours() != null && c.maxGpuHours() > f.gpu())))
                refusal = "Submitted capacity override exceeds the facility";
        }
        try { return json.writeValueAsString(new Snapshot(request, versions, f, refusal, reserved)); }
        catch (Exception ex) { throw new IllegalStateException("Cannot capture schedule inputs", ex); }
    }

    private boolean matches(OptimizeRequestDto.JobInput i, Job j) {
        return Objects.equals(i.cpuHours(), j.getCpuHours()) && Objects.equals(i.gpuHours(), j.getGpuHours()) &&
            Objects.equals(i.originalStartTime(), j.earliestAllowedStart()) && Objects.equals(i.deadline(), j.getDeadline()) &&
            Objects.equals(i.flexibilityWindowHours(), j.getFlexibilityWindowHours()) &&
            Objects.equals(i.isExempt(), j.isExempt()) &&
            JobPriority.fromString(i.priority()) == j.getPriority();
    }

    /** Returns a refusal instead of throwing, so the caller can commit the invalidation. */
    public String mismatch(OptimizationPlan plan, String email) {
        if (plan.getInputSnapshot() == null) return "Missing input provenance; generate a new recommendation";
        try {
            Snapshot s = json.readValue(plan.getInputSnapshot(), Snapshot.class);
            if (s.refusal() != null) return s.refusal();
            if (s.request() == null || s.request().jobs() == null || s.jobs() == null || s.facility() == null ||
                s.jobs().size() != s.request().jobs().size() || !s.jobs().keySet().equals(plan.getJobIds()))
                return "Incomplete input provenance";
            if (!s.facility().equals(facility())) return "Facility scheduling inputs changed";
            for (var entry : s.jobs().entrySet()) {
                if (plan.getRemovedJobIds().contains(entry.getKey())) continue;
                Job j = jobs.findByIdAndOwnerEmail(entry.getKey(), email).orElse(null);
                if (j == null) return "job " + entry.getKey() + " is no longer in your jobs";
                if (j.getSchedulingRevision() != entry.getValue().revision() || !state(j).equals(entry.getValue().state()))
                    return "Scheduling inputs changed for job " + j.getId();
                if ("CANCELLED".equals(j.getStatus())) return "Job " + j.getId() + " is cancelled";
                if (j.getConfirmationOutcome() != null && j.getConfirmationOutcome() != ConfirmationOutcome.CONFIRMED)
                    return "Structured constraints are not confirmed for job " + j.getId();
            }
            return null;
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(ScheduleInputs.class).warn("mismatch() failed for plan {}", plan.getRunId(), ex);
            return "Unreadable input provenance; generate a new recommendation";
        }
    }

    /** The revision of each job as the run solved it, from the stored provenance. Empty when unreadable. */
    public Map<Long, Long> revisions(String provenance) {
        try {
            Map<Long, Long> revisions = new TreeMap<>();
            json.readValue(provenance, Snapshot.class).jobs().forEach((id, version) -> revisions.put(id, version.revision()));
            return revisions;
        } catch (Exception ex) { return Map.of(); }
    }

    /** The revision a stored plan recorded for one job, or null when it recorded none. */
    public Long revisionIn(String provenance, Long jobId) {
        return provenance == null ? null : revisions(provenance).get(jobId);
    }

    /** What approved plans used when this plan was solved, by hour. */
    public Map<LocalDateTime, CapacityLimit> reservedAtSolve(OptimizationPlan plan) {
        try { return json.readValue(plan.getInputSnapshot(), Snapshot.class).reservedOrEmpty(); }
        catch (Exception ex) { return Map.of(); }
    }

    /** The latest deadline among the jobs this plan was solved for, or null when its provenance is unreadable. */
    public LocalDateTime latestDeadline(OptimizationPlan plan) {
        try {
            return json.readValue(plan.getInputSnapshot(), Snapshot.class).request().jobs().stream()
                .map(OptimizeRequestDto.JobInput::deadline).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        } catch (Exception ex) { return null; }
    }
}
