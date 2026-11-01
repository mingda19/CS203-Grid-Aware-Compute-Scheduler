package com.gacs.backend.service.constraintparse;

import com.gacs.backend.dto.ConstraintParseRequestDto;
import com.gacs.backend.dto.ConstraintParseRequestDto.Draft;
import com.gacs.backend.dto.ConstraintParseResponseDto;
import com.gacs.backend.model.JobPriority;
import com.gacs.backend.service.JobService;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Conflict;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Evidence;
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
import java.util.List;
import java.util.Objects;

/**
 * Parses constraint text, applies it to the job form's current values, and checks the result with the job
 * API's own rules (docs/constraint-parser-plan.md section 3.4). Never saves anything.
 */
@Service
public class ConstraintParseService {

    private final ConstraintParser parser;
    private final JobService jobService;
    private final Clock clock;
    private final ZoneId defaultZone;

    public ConstraintParseService(ConstraintParser parser, JobService jobService, Clock clock,
                                  @Value("${gacs.parser.default-zone:Asia/Singapore}") String defaultZone) {
        this.parser = parser;
        this.jobService = jobService;
        this.clock = clock;
        this.defaultZone = ZoneId.of(defaultZone);
    }

    public ConstraintParseResponseDto parse(ConstraintParseRequestDto request) {
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

        String status;
        if (validationError != null) {
            status = ConstraintParseResponseDto.INVALID;
        } else if (!parsed.ambiguities().isEmpty() || !conflicts.isEmpty() || !parsed.hasAnyField()) {
            status = ConstraintParseResponseDto.NEEDS_CLARIFICATION;
        } else {
            status = ConstraintParseResponseDto.PARSED;
        }

        return new ConstraintParseResponseDto(status,
                new ConstraintParseResponseDto.Fields(start, deadline, flexibility, priority, exempt),
                parsed.evidence(), parsed.ambiguities(), conflicts, parsed.unsupported(), validationError,
                LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MICROS), zone.getId());
    }

    private ZoneId zone(String requested) {
        if (requested == null || requested.isBlank()) {
            return defaultZone;
        }
        try {
            return ZoneId.of(requested.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown timeZone: '" + requested + "'. Use an IANA zone such as Asia/Singapore");
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
