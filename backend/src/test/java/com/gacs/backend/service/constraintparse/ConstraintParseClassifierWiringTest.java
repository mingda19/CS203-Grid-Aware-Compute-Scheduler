package com.gacs.backend.service.constraintparse;

import com.gacs.backend.dto.ConstraintParseRequestDto;
import com.gacs.backend.dto.ConstraintParseResponseDto;
import com.gacs.backend.dto.JobResponseDto;
import com.gacs.backend.service.JobService;
import com.gacs.backend.service.JobStatusResolver;
import com.gacs.backend.service.ScheduleInputs;
import com.gacs.backend.service.allocation.ConstraintType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The classifier's path through {@link ConstraintParseService}: that it is reached at all, that it
 * is scoped to the operator's own jobs, and that it cannot take the rule-based parse down with it.
 */
class ConstraintParseClassifierWiringTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final com.gacs.backend.repository.JobRepository repository =
            mock(com.gacs.backend.repository.JobRepository.class);
    private final JobService jobService = new JobService(repository, mock(ScheduleInputs.class), CLOCK,
            mock(com.gacs.backend.repository.OptimizationPlanRepository.class),
            mock(com.gacs.backend.repository.WorkloadScheduleRepository.class),
            mock(com.gacs.backend.service.AuditEventService.class),
            mock(com.gacs.backend.repository.UserRepository.class), statusesFromRow());

    /** Job status is derived from plans; here a finished row stays finished and the rest are still scheduling. */
    @SuppressWarnings("unchecked")
    private static JobStatusResolver statusesFromRow() {
        JobStatusResolver statuses = mock(JobStatusResolver.class);
        when(statuses.resolve(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(call -> {
            var derived = new java.util.HashMap<Long, JobStatusResolver.Derived>();
            for (com.gacs.backend.model.Job job : (java.util.Collection<com.gacs.backend.model.Job>) call.getArgument(0)) {
                var status = Set.of("COMPLETED", "CANCELLED").contains(job.getStatus())
                        ? JobStatusResolver.Status.valueOf(job.getStatus()) : JobStatusResolver.Status.SCHEDULING;
                derived.put(job.getId(), new JobStatusResolver.Derived(status, null, null, false, JobStatusResolver.Cause.NONE));
            }
            return derived;
        });
        return statuses;
    }

    /** A classifier that records what it was asked and returns what the test tells it to. */
    private static final class Stub implements ConstraintClassifier {
        final AtomicReference<List<JobReferenceResolver.Candidate>> sawCandidates =
                new AtomicReference<>(null);
        private final List<ClassifiedConstraint> result;
        private final RuntimeException failure;

        Stub(List<ClassifiedConstraint> result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public List<ClassifiedConstraint> classify(String text, Set<ConstraintType> allowed) {
            return classify(text, allowed, List.of(), null);
        }

        @Override
        public List<ClassifiedConstraint> classify(String text, Set<ConstraintType> allowed,
                                                   List<JobReferenceResolver.Candidate> candidates,
                                                   String editingJob) {
            sawCandidates.set(candidates);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private ConstraintParseService serviceWith(Stub stub) {
        return new ConstraintParseService(new RuleBasedConstraintParser(), jobService, CLOCK,
                "America/Chicago", Optional.of(stub), Optional.of(new ConstraintGuardrail(0.7, 0.3)));
    }

    private static ConstraintParseRequestDto request(String text) {
        return new ConstraintParseRequestDto(text, null, "America/Chicago");
    }

    private static JobResponseDto job(long id, String name) {
        return JobResponseDto.builder().id(id).name(name).build();
    }

    @Test
    void withNoClassifierConfigured_TheParseStillWorksAndSuggestsNothing() {
        ConstraintParseService service = new ConstraintParseService(
                new RuleBasedConstraintParser(), jobService, CLOCK, "America/Chicago");

        ConstraintParseResponseDto result = service.parse(request("Finish by 6pm tomorrow."));

        assertThat(result.fields().deadline()).isNotNull();
        assertThat(result.suggestions()).isEmpty();
        assertThat(result.disagreements()).isEmpty();
    }

    @Test
    void aClassifierFailureDoesNotFailTheParse() {
        // The rule-based fields are correct and complete without the classifier, so taking the whole
        // request down over an optional extra would turn a degraded feature into an outage.
        Stub failing = new Stub(null, new IllegalStateException("TypeSafe is down"));

        ConstraintParseResponseDto result = serviceWith(failing).parse(request("Finish by 6pm tomorrow."));

        assertThat(result.fields().deadline()).isNotNull();
        assertThat(result.suggestions()).isEmpty();
        assertThat(result.disagreements()).isEmpty();
        assertThat(result.status()).isEqualTo(ConstraintParseResponseDto.PARSED);
    }

    @Test
    void onlyTheOperatorsOwnJobsAreOfferedAsCandidates() {
        when(repository.findByOwnerEmailOrderByOriginalStartTimeAsc("me@example.com"))
                .thenReturn(List.of());
        Stub stub = new Stub(List.of(), null);
        ConstraintParseService service = serviceWith(stub);

        // Returning the DTOs through JobService is what enforces the scoping; this asserts the
        // service asks for one operator's jobs and passes exactly those through.
        service.parse(request("Finish by 6pm tomorrow."), "me@example.com");

        assertThat(stub.sawCandidates.get()).isEmpty();
    }

    @Test
    void candidatesAreOpenJobsOnlyNewestFirstAndCapped() {
        // Jev's Choice rejects more than 255 options, and every option is billed.
        List<com.gacs.backend.model.Job> owned = new java.util.ArrayList<>();
        for (long id = 1; id <= ConstraintParseService.MAX_CANDIDATES + 10; id++) {
            owned.add(com.gacs.backend.model.Job.builder().id(id).name("job " + id).status("PENDING").build());
        }
        long newest = ConstraintParseService.MAX_CANDIDATES + 10;
        owned.get((int) newest - 1).setStatus("COMPLETED");
        owned.get((int) newest - 2).setStatus("CANCELLED");
        when(repository.findByOwnerEmailOrderByOriginalStartTimeAsc("me@example.com")).thenReturn(owned);
        Stub stub = new Stub(List.of(), null);

        serviceWith(stub).parse(request("Finish by 6pm tomorrow."), "me@example.com");

        List<Long> ids = stub.sawCandidates.get().stream().map(JobReferenceResolver.Candidate::id).toList();
        assertThat(ids).hasSize(ConstraintParseService.MAX_CANDIDATES)
                .doesNotContain(newest, newest - 1)
                .startsWith(newest - 2)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void withNoAuthenticatedOperator_NoJobsAreOffered() {
        // A constraint may never bind a job nobody owns, so with no operator the referent questions
        // are not asked at all rather than asked against every job in the system.
        Stub stub = new Stub(List.of(), null);

        serviceWith(stub).parse(request("Finish by 6pm tomorrow."), null);

        assertThat(stub.sawCandidates.get()).isEmpty();
    }

    @Test
    void aClassifierOnlyConstraintComesBackAsASuggestion() {
        Stub stub = new Stub(List.of(ClassifiedConstraint.of(ConstraintType.ABSOLUTE_TIME_WINDOW,
                0.87, "only ever overnight", 0, 19)), null);

        ConstraintParseResponseDto result = serviceWith(stub)
                .parse(request("only ever overnight"), "me@example.com");

        assertThat(result.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("ABSOLUTE_TIME_WINDOW");
            assertThat(s.probability()).isEqualTo(0.87);
        });
    }

    @Test
    void aDisagreementMakesTheParseNeedClarification() {
        // The parser reads a deadline; the classifier reports nothing. Neither wins, so the operator
        // is asked - and the status has to say so, or the form would let them save straight past it.
        Stub silent = new Stub(List.of(), null);

        ConstraintParseResponseDto result =
                serviceWith(silent).parse(request("Finish by 6pm tomorrow."), "me@example.com");

        assertThat(result.disagreements()).isNotEmpty();
        assertThat(result.status()).isEqualTo(ConstraintParseResponseDto.NEEDS_CLARIFICATION);
    }

    @Test
    void aClassifierWithNoGuardrailIsTreatedAsNoClassifier() {
        // Unverified proposals must never reach an operator, so a misconfiguration fails closed.
        Stub stub = new Stub(List.of(ClassifiedConstraint.of(ConstraintType.ABSOLUTE_TIME_WINDOW,
                0.99, "only ever overnight", 0, 19)), null);
        ConstraintParseService unguarded = new ConstraintParseService(new RuleBasedConstraintParser(),
                jobService, CLOCK, "America/Chicago", Optional.of(stub), Optional.empty());

        ConstraintParseResponseDto result = unguarded.parse(request("only ever overnight"), "me@example.com");

        assertThat(result.suggestions()).isEmpty();
        assertThat(stub.sawCandidates.get()).isNull();
    }

    @Test
    void blankTextNeverReachesTheClassifier() {
        Stub stub = new Stub(List.of(), null);

        serviceWith(stub).parse(new ConstraintParseRequestDto("   ", null, "America/Chicago"),
                "me@example.com");

        assertThat(stub.sawCandidates.get()).isNull();
    }
}
