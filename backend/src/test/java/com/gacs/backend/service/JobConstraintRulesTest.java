package com.gacs.backend.service;

import com.gacs.backend.model.*;
import com.gacs.backend.service.allocation.ConstraintType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class JobConstraintRulesTest {
    private Job job(long id, JobConstraint... rules) {
        return Job.builder().id(id).ownerEmail("owner").pairConstraints(List.of(rules))
                .confirmationOutcome(ConfirmationOutcome.CONFIRMED).build();
    }

    @Test void validatesOwnershipAndSelfReferences() {
        Job first = job(1, new JobConstraint(ConstraintType.PRECEDENCE, 2L));
        assertThatThrownBy(() -> JobConstraintRules.validate(first, List.of(first)))
                .hasMessageContaining("not one of your jobs");
        first.setPairConstraints(List.of(new JobConstraint(ConstraintType.PRECEDENCE, 1L)));
        assertThatThrownBy(() -> JobConstraintRules.validate(first, List.of(first)))
                .hasMessageContaining("itself");
    }

    @Test void rejectsMixedDirectionCyclesButAllowsAnAcyclicChain() {
        Job first = job(1, new JobConstraint(ConstraintType.PRECEDENCE, 2L));
        Job second = job(2, new JobConstraint(ConstraintType.PRECEDENCE, 3L));
        Job third = job(3);
        assertThatCode(() -> JobConstraintRules.validate(first, List.of(first, second, third))).doesNotThrowAnyException();
        first.setPairConstraints(List.of(new JobConstraint(ConstraintType.DEPENDENCY, 3L)));
        third.setPairConstraints(List.of(new JobConstraint(ConstraintType.DEPENDENCY, 1L)));
        assertThatThrownBy(() -> JobConstraintRules.validate(first, List.of(first, second, third)))
                .hasMessageContaining("cycle");
    }

    @Test void rejectsUnconfirmedOrUnsupportedRules() {
        Job first = job(1, new JobConstraint(ConstraintType.PRECEDENCE, 2L));
        first.setConfirmationOutcome(ConfirmationOutcome.PENDING);
        assertThatThrownBy(() -> JobConstraintRules.validate(first, List.of(first, job(2))))
                .hasMessageContaining("Confirm");
        first.setConfirmationOutcome(ConfirmationOutcome.CONFIRMED);
        first.setPairConstraints(List.of(new JobConstraint(ConstraintType.DEADLINE, 2L)));
        assertThatThrownBy(() -> JobConstraintRules.validate(first, List.of(first, job(2))))
                .hasMessageContaining("supported type");
    }
}
