package com.gacs.backend.service;

import com.gacs.backend.model.Job;
import com.gacs.backend.model.JobConstraint;
import com.gacs.backend.service.allocation.ConstraintType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class JobPlanningGroupsTest {
    @Test void fallbackKeepsTransitiveRelationshipsTogetherRegardlessOfInputOrder() {
        Job first = Job.builder().id(1L).pairConstraints(List.of(new JobConstraint(ConstraintType.PRECEDENCE, 2L))).build();
        Job second = Job.builder().id(2L).build();
        Job third = Job.builder().id(3L).pairConstraints(List.of(new JobConstraint(ConstraintType.DEPENDENCY, 2L))).build();
        Job unrelated = Job.builder().id(4L).build();
        var groups = JobPlanningService.constraintGroups(List.of(second, unrelated, third, first));
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0)).containsExactlyInAnyOrder(first, second, third);
        assertThat(groups.get(1)).containsExactly(unrelated);
    }
}
