package com.gacs.backend;

import com.gacs.backend.model.*;
import com.gacs.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:grid55-plan-query;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchedulePlanQueryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired OptimizerRunRepository runs;
    @Autowired OptimizationPlanRepository plans;

    private User owner() {
        return users.save(new User(UUID.randomUUID() + "@test.io", "password", "Operator", "ROLE_USER"));
    }

    private String plan(User owner, Instant createdAt, AuditActionType action) {
        var run = runs.save(OptimizerRun.builder().id(UUID.randomUUID().toString()).createdAt(createdAt)
            .solverName("test").solverVersion("1").solverConfig("{}").build());
        var plan = new OptimizationPlan(run, owner, "{}", List.of(), createdAt);
        if (action != null) plan.recordDecision(action);
        return plans.saveAndFlush(plan).getRunId();
    }

    @Test void listsOnlyOwnersPlansNewestFirstWithPagination() throws Exception {
        var owner = owner();
        var other = owner();
        String older = plan(owner, Instant.parse("2026-10-30T10:00:00Z"), null);
        String newer = plan(owner, Instant.parse("2026-10-31T10:00:00Z"), AuditActionType.REJECT);
        plan(other, Instant.parse("2026-11-01T10:00:00Z"), null);
        mvc.perform(get("/api/schedules?page=0&size=1").with(user(owner.getEmail())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.totalPages").value(2))
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].runId").value(newer))
            .andExpect(jsonPath("$.data.content[0].reviewState").value("REJECTED"));
        mvc.perform(get("/api/schedules?page=1&size=1").with(user(owner.getEmail()).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].runId").value(older));
    }

    @Test void returnsEmptyPageForOperatorWithNoPlans() throws Exception {
        var owner = owner();
        mvc.perform(get("/api/schedules").with(user(owner.getEmail())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test void rejectsUnauthenticatedAndDeletedOperators() throws Exception {
        mvc.perform(get("/api/schedules")).andExpect(status().isForbidden());
        mvc.perform(get("/api/schedules").with(user("deleted-operator@test.io")))
            .andExpect(status().isUnauthorized());
    }

    @Test void rejectsInvalidPagination() throws Exception {
        var owner = owner();
        for (String query : List.of("page=-1", "size=0", "size=101"))
            mvc.perform(get("/api/schedules?" + query).with(user(owner.getEmail())))
                .andExpect(status().isBadRequest());
    }
}
