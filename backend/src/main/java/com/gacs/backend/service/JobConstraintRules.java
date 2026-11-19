package com.gacs.backend.service;

import com.gacs.backend.model.*;
import com.gacs.backend.service.allocation.ConstraintType;
import java.util.*;

/** Validates saved relationships against one owner's jobs, including mixed-direction cycles. */
final class JobConstraintRules {
    private static final Set<ConstraintType> PAIR_TYPES = Set.of(
            ConstraintType.PRECEDENCE, ConstraintType.DEPENDENCY, ConstraintType.MUTUAL_EXCLUSION);

    private JobConstraintRules() { }

    static void validate(Job changed, List<Job> owned) {
        List<JobConstraint> rules = changed.getPairConstraints();
        if (rules.size() > 25) throw new IllegalArgumentException("At most 25 pair constraints may be attached to a job");
        if (!rules.isEmpty() && changed.getConfirmationOutcome() != ConfirmationOutcome.CONFIRMED)
            throw new IllegalArgumentException("Confirm the parsed pair constraints before saving");
        Map<Long, Job> byId = new HashMap<>();
        owned.forEach(job -> byId.put(job.getId(), job));
        long changedId = changed.getId() == null ? -1 : changed.getId();
        for (JobConstraint rule : rules) {
            if (rule == null || rule.type() == null || !PAIR_TYPES.contains(rule.type()) || rule.referencedJobId() == null)
                throw new IllegalArgumentException("A pair constraint needs a supported type and a referenced job id");
            if (rule.referencedJobId() == changedId)
                throw new IllegalArgumentException("A job cannot be constrained against itself");
            Job other = byId.get(rule.referencedJobId());
            if (other == null)
                throw new IllegalArgumentException("Referenced job is not one of your jobs: " + rule.referencedJobId());
            if ("CANCELLED".equals(other.getStatus()))
                throw new IllegalArgumentException("A pair constraint cannot reference a cancelled job");
        }
        byId.put(changedId, changed);
        Map<Long, Set<Long>> edges = new HashMap<>();
        Map<Long, Integer> degree = new HashMap<>();
        byId.keySet().forEach(id -> degree.put(id, 0));
        byId.forEach((id, job) -> {
            for (JobConstraint rule : job.getPairConstraints()) {
                if (rule.type() == ConstraintType.MUTUAL_EXCLUSION) continue;
                long before = rule.type() == ConstraintType.PRECEDENCE ? id : rule.referencedJobId();
                long after = rule.type() == ConstraintType.PRECEDENCE ? rule.referencedJobId() : id;
                if (edges.computeIfAbsent(before, key -> new HashSet<>()).add(after))
                    degree.merge(after, 1, Integer::sum);
                degree.putIfAbsent(before, 0);
            }
        });
        Deque<Long> ready = new ArrayDeque<>();
        degree.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            long id = ready.remove();
            visited++;
            for (long next : edges.getOrDefault(id, Set.of()))
                if (degree.merge(next, -1, Integer::sum) == 0) ready.add(next);
        }
        if (visited != degree.size()) throw new IllegalArgumentException("Pair constraints contain a precedence cycle");
    }
}
