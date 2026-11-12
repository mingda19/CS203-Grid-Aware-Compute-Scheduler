package com.gacs.backend.service.constraintparse;

import com.gacs.backend.dto.ConstraintParseRequestDto;
import com.gacs.backend.dto.ConstraintParseRequestDto.Draft;
import com.gacs.backend.dto.ConstraintParseResponseDto;
import com.gacs.backend.dto.JobResponseDto;
import com.gacs.backend.model.JobPriority;
import com.gacs.backend.service.allocation.ConstraintType;
import com.gacs.backend.service.JobService;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Conflict;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Evidence;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Unsupported;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Parses constraint text, applies it to the job form's current values, and checks the result with the job
 * API's own rules (docs/constraint-parser-plan.md section 3.4). Never saves anything.
 */
@Service
public class ConstraintParseService {

    /** Types the classifier is asked about. The rest the parser handles alone, or nothing does. */
    private static final Set<ConstraintType> CLASSIFIED = EnumSet.of(
            ConstraintType.DEADLINE, ConstraintType.FLEXIBILITY_WINDOW, ConstraintType.ABSOLUTE_TIME_WINDOW,
            ConstraintType.PRECEDENCE, ConstraintType.DEPENDENCY, ConstraintType.MUTUAL_EXCLUSION,
            ConstraintType.SYNCHRONIZED_FINISH);
    private static final Set<ConstraintType> PAIR_TYPES = EnumSet.of(
            ConstraintType.PRECEDENCE, ConstraintType.DEPENDENCY, ConstraintType.MUTUAL_EXCLUSION);

    private static final Logger logger = LoggerFactory.getLogger(ConstraintParseService.class);

    static final int MAX_CANDIDATES = 50;
    private static final Set<String> FINISHED_STATUSES = Set.of("COMPLETED", "CANCELLED");

    private final ConstraintParser parser;
    private final JobService jobService;
    private final Clock clock;
    private final ZoneId defaultZone;
    private final Optional<ConstraintClassifier> classifier;
    /** Optional because it is only reachable through the classifier; see {@link #classify}. */
    private final Optional<ConstraintGuardrail> guardrail;

    /** {@code @Autowired} because the rule-based convenience constructor below is also public, and
     *  Spring will not choose between two candidates on its own. */
    @Autowired
    public ConstraintParseService(ConstraintParser parser, JobService jobService, Clock clock,
                                  @Value("${gacs.parser.default-zone:America/Chicago}") String defaultZone,
                                  Optional<ConstraintClassifier> classifier,
                                  Optional<ConstraintGuardrail> guardrail) {
        this.parser = parser;
        this.jobService = jobService;
        this.clock = clock;
        this.defaultZone = ZoneId.of(defaultZone);
        this.classifier = classifier;
        this.guardrail = guardrail;
    }

    /**
     * Rule-based parsing only, with no classifier and no guardrail.
     *
     * <p>Not a test seam: this is the shape the service runs in whenever no TypeSafe key is
     * configured, which is the supported default for local development. Keeping it a real
     * constructor says so, and means the rule-based path can be constructed and reasoned about
     * without reference to a classifier that may not exist.
     */
    public ConstraintParseService(ConstraintParser parser, JobService jobService, Clock clock,
                                  String defaultZone) {
        this(parser, jobService, clock, defaultZone, Optional.empty(), Optional.empty());
    }

    /**
     * Parses without a classifier. The rule-based path is complete on its own, so this stays
     * supported rather than becoming a half-configured special case.
     */
    public ConstraintParseResponseDto parse(ConstraintParseRequestDto request) {
        return parse(request, null);
    }

    /**
     * @param ownerEmail the authenticated operator, used only to look up the jobs a pair constraint
     *                   could refer to. Null skips the classifier's job-reference questions
     *                   entirely. A constraint may never bind a job the operator does not own, so
     *                   the candidate list is theirs and is never widened.
     */
    public ConstraintParseResponseDto parse(ConstraintParseRequestDto request, String ownerEmail) {
        ZoneId zone = zone(request.timeZone());
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        ParsedConstraints parsed = parser.parse(request.rawConstraints(), now);
        Draft draft = request.draft() == null ? Draft.EMPTY : request.draft();

        // The text wins over the form; a difference is a conflict the operator confirms.
        List<Conflict> conflicts = new ArrayList<>(parsed.conflicts());
        LocalDateTime start = merge(ConstraintField.ORIGINAL_START_TIME, parsed.originalStartTime(),
                draft.originalStartTime(), parsed, conflicts);
        LocalDateTime deadline = merge(ConstraintField.DEADLINE, parsed.deadline(), draft.deadline(), parsed, conflicts);
        Integer flexibility = merge(ConstraintField.FLEXIBILITY_WINDOW_HOURS, parsed.flexibilityWindowHours(),
                draft.flexibilityWindowHours(), parsed, conflicts);
        Boolean exempt = merge(ConstraintField.IS_EXEMPT, parsed.isExempt(), draft.isExempt(), parsed, conflicts);
        String priority = merge(ConstraintField.PRIORITY,
                parsed.priority() == null ? null : parsed.priority().name(), normalisedPriority(draft.priority()),
                parsed, conflicts);

        String validationError = null;
        try {
            jobService.validateTiming(start, deadline, flexibility);
            jobService.validatePriority(priority);
        } catch (IllegalArgumentException e) {
            validationError = e.getMessage();
        }

        Classification classification = classify(request.rawConstraints(), parsed, ownerEmail, request.editingJobId());
        var reconciliation = classification.reconciliation();
        // A whole-message classification is not evidence that every clause was understood. Only
        // an exact phrase match can claim an unsupported clause; unrelated text stays visible.
        List<Unsupported> unsupported = parsed.unsupported().stream()
                .filter(u -> classification.accepted().stream().noneMatch(a ->
                        a.referencedJobId() != null && recognisedPhrase(a.proposal().phrase()).equals(recognisedPhrase(u.phrase()))))
                .toList();
        String status;
        if (validationError != null) {
            status = ConstraintParseResponseDto.INVALID;
        } else if (!parsed.ambiguities().isEmpty() || !conflicts.isEmpty() || !unsupported.isEmpty()
                || (!parsed.hasAnyField() && reconciliation.suggestions().isEmpty())) {
            status = ConstraintParseResponseDto.NEEDS_CLARIFICATION;
        } else {
            status = ConstraintParseResponseDto.PARSED;
        }

        if (!reconciliation.disagreements().isEmpty() && !ConstraintParseResponseDto.INVALID.equals(status)) {
            // A disagreement is an open question, so the operator has to come back to it - exactly
            // what NEEDS_CLARIFICATION already means. INVALID outranks it: a field that breaks a job
            // rule has to be fixed before any of this is worth asking about.
            status = ConstraintParseResponseDto.NEEDS_CLARIFICATION;
        }

        return new ConstraintParseResponseDto(status,
                new ConstraintParseResponseDto.Fields(start, deadline, flexibility, priority, exempt),
                parsed.evidence(), parsed.ambiguities(), conflicts, unsupported, validationError,
                LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MICROS), zone.getId(),
                reconciliation.suggestions().stream().map(s -> new ConstraintParseResponseDto.Suggestion(
                        s.type().name(), s.probability(), s.phrase(), s.referencedJobId(), s.needsConfirmation()))
                        .toList(),
                reconciliation.disagreements(), classification.status(), classification.warnings());
    }

    private static String recognisedPhrase(String phrase) {
        return phrase.strip().replaceAll("[.!?]+$", "").strip();
    }

    private record Classification(ClassifierReconciler.Reconciliation reconciliation,
                                  List<ConstraintGuardrail.Accepted> accepted,
                                  String status, List<String> warnings) {
        static Classification unavailable(String status, String message) {
            return new Classification(ClassifierReconciler.Reconciliation.NONE, List.of(), status, List.of(message));
        }
    }

    /**
     * Runs the classifier and reconciles it with the parser, or returns nothing.
     *
     * <p>A classifier failure is swallowed here, and only here. Everywhere below this the rule is
     * the opposite - {@link JevConstraintClassifier} throws rather than returning "no constraints",
     * because a dropped call and an empty answer are indistinguishable once flattened. At this
     * level the distinction has been made and acted on: the parse itself succeeded, the rule-based
     * fields are correct and complete, and the only thing lost is the extra suggestions. Failing
     * the whole request would take a working feature down with an optional one.
     */
    private Classification classify(String text, ParsedConstraints parsed,
                                    String ownerEmail, Long editingJobId) {
        if (classifier.isEmpty() || text == null || text.isBlank()) {
            return Classification.unavailable("DISABLED", "JEV is not enabled. Only timing, flexibility, priority and exemption can be recognised.");
        }
        if (guardrail.isEmpty()) {
            // A classifier with no guardrail is a misconfiguration, and the safe reading of it is
            // "no classification" rather than "classification, unchecked". Nothing the classifier
            // returns is trusted until the guardrail has re-checked it against the operator's own
            // text, so running without one would put unverified proposals in front of an operator.
            logger.error("A constraint classifier is configured without a ConstraintGuardrail; "
                    + "skipping classification rather than using unchecked proposals");
            return Classification.unavailable("FAILED", "JEV could not run. Try parsing again; pair constraints have not been recognised.");
        }
        try {
            List<JobReferenceResolver.Candidate> candidates = candidatesFor(ownerEmail);
            String editingName = candidates.stream().filter(c -> Objects.equals(c.id(), editingJobId))
                    .map(JobReferenceResolver.Candidate::name).findFirst().orElse(null);
            candidates = candidates.stream().filter(c -> !Objects.equals(c.id(), editingJobId)).toList();
            List<ClassifiedConstraint> proposals = classifier.get().classify(text, CLASSIFIED, candidates, editingName);
            ConstraintGuardrail.Review review = guardrail.get()
                    .review(text, proposals, CLASSIFIED, editingJobId, candidates);
            List<ConstraintGuardrail.Accepted> accepted = new ArrayList<>(review.accepted());
            List<String> warnings = new ArrayList<>(review.refused().stream().map(ConstraintGuardrail.Refused::message).toList());
            // The model cites the whole message. For mixed text, ask about the unclaimed clauses
            // separately instead of letting a pair reading hide unrelated unsupported content.
            if (accepted.stream().anyMatch(a -> PAIR_TYPES.contains(a.proposal().type()))) {
                for (Unsupported clause : parsed.unsupported().stream().limit(8).toList()) {
                    if (accepted.stream().anyMatch(a -> PAIR_TYPES.contains(a.proposal().type())
                            && recognisedPhrase(a.proposal().phrase()).equals(recognisedPhrase(clause.phrase())))) continue;
                    try {
                        var local = classifier.get().classify(clause.phrase(), PAIR_TYPES, candidates, editingName);
                        var checked = guardrail.get().review(clause.phrase(), local, PAIR_TYPES, editingJobId, candidates);
                        for (var entry : checked.accepted()) {
                            var p = entry.proposal();
                            var bound = new ClassifiedConstraint(p.type(), p.probability(), p.phrase(),
                                    clause.start() + p.start(), clause.start() + p.end(), p.referent());
                            // Prefer the clause's evidence to a duplicate whole-message suggestion.
                            accepted.removeIf(a -> a.proposal().type() == p.type()
                                    && Objects.equals(a.referencedJobId(), entry.referencedJobId()));
                            accepted.add(new ConstraintGuardrail.Accepted(bound, entry.referencedJobId(), entry.needsConfirmation()));
                        }
                    } catch (RuntimeException failedClause) {
                        warnings.add("JEV could not check part of this text. Parse that relationship on its own, or try again.");
                    }
                }
            }
            return new Classification(ClassifierReconciler.reconcile(parsed, accepted, CLASSIFIED),
                    accepted, "SUCCEEDED", warnings);
        } catch (RuntimeException e) {
            logger.warn("Constraint classification failed; returning the rule-based parse only: {}",
                    e.getMessage());
            return Classification.unavailable("FAILED", "JEV could not classify this text. Try parsing again; the values shown come from the rule-based parser only.");
        }
    }

    /**
     * The operator's own open jobs, newest first, as the only things a pair constraint may be bound
     * to. Capped because every option is billed and Jev's Choice type rejects more than 255.
     */
    private List<JobReferenceResolver.Candidate> candidatesFor(String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            return List.of();
        }
        return jobService.getJobsByOwner(ownerEmail).stream()
                .filter(job -> job.getId() != null && job.getName() != null)
                .filter(job -> !FINISHED_STATUSES.contains(job.getStatus()))
                .sorted(Comparator.comparing(JobResponseDto::getId).reversed())
                .limit(MAX_CANDIDATES)
                .map(job -> new JobReferenceResolver.Candidate(job.getId(), job.getName()))
                .toList();
    }

    private ZoneId zone(String requested) {
        if (requested == null || requested.isBlank()) {
            return defaultZone;
        }
        try {
            return ZoneId.of(requested.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown timeZone: '" + requested + "'. Use an IANA zone such as America/Chicago");
        }
    }

    private static <T> T merge(ConstraintField field, T fromText, T fromForm, ParsedConstraints parsed,
                               List<Conflict> conflicts) {
        if (fromText == null) {
            return fromForm;
        }
        if (fromForm != null && !Objects.equals(fromText, fromForm)) {
            String phrase = parsed.evidence().stream().filter(e -> e.field() == field).map(Evidence::phrase)
                    .findFirst().orElse(String.valueOf(fromText));
            conflicts.add(new Conflict(field, List.of(phrase, "form: " + fromForm), "The text sets "
                    + field.apiName() + " to " + fromText + " but the form has " + fromForm
                    + ". The text's value is used; please confirm."));
        }
        return fromText;
    }

    /** The form's priority in the same spelling the parser uses; invalid values are left for the validator. */
    private static String normalisedPriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return null;
        }
        try {
            return JobPriority.fromString(priority).name();
        } catch (IllegalArgumentException e) {
            return priority;
        }
    }
}
