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
    @Autowired com.gacs.backend.service.AppSettingsService settings;

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

    @Test void listsEveryOwnersPlansNewestFirstWithPagination() throws Exception {
        var owner = owner();
        var other = owner();
        String older = plan(owner, Instant.parse("2026-10-30T10:00:00Z"), null);
        String newer = plan(owner, Instant.parse("2026-10-31T10:00:00Z"), AuditActionType.REJECT);
        String othersPlan = plan(other, Instant.parse("2026-11-01T10:00:00Z"), null);
        mvc.perform(get("/api/schedules?page=0&size=1").with(user(owner.getEmail())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.totalPages").value(3))
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].runId").value(othersPlan))
            .andExpect(jsonPath("$.data.content[0].ownerName").value("Operator"))
            .andExpect(jsonPath("$.data.content[0].canApprove").value(true));
        mvc.perform(get("/api/schedules?page=1&size=1").with(user(owner.getEmail())))
            .andExpect(jsonPath("$.data.content[0].runId").value(newer))
            .andExpect(jsonPath("$.data.content[0].reviewState").value("REJECTED"))
            .andExpect(jsonPath("$.data.content[0].canApprove").value(false));
        mvc.perform(get("/api/schedules?page=2&size=1").with(user(owner.getEmail()).roles("ADMIN")))
            .andExpect(jsonPath("$.data.content[0].runId").value(older));
    }

    @Test void stateFilterKeepsOnlyThatStateNewestFirstAndCountsOnlyThem() throws Exception {
        var owner = owner();
        var other = owner();
        String oldPending = plan(owner, Instant.parse("2026-10-30T10:00:00Z"), null);
        plan(owner, Instant.parse("2026-10-31T10:00:00Z"), AuditActionType.REJECT);
        String newPending = plan(owner, Instant.parse("2026-11-01T10:00:00Z"), null);
        String othersPending = plan(other, Instant.parse("2026-11-02T10:00:00Z"), null);
        mvc.perform(get("/api/schedules?state=PENDING").with(user(owner.getEmail())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.content.length()").value(3))
            .andExpect(jsonPath("$.data.content[0].runId").value(othersPending))
            .andExpect(jsonPath("$.data.content[1].runId").value(newPending))
            .andExpect(jsonPath("$.data.content[2].runId").value(oldPending))
            .andExpect(jsonPath("$.data.content[0].reviewState").value("PENDING"));
        // No filter: all four plans, whoever owns them.
        mvc.perform(get("/api/schedules").with(user(owner.getEmail())))
            .andExpect(jsonPath("$.data.totalElements").value(4));
    }

    @Test void approvableKeepsOnlyPlansTheCallerMayApprove() throws Exception {
        var owner = owner();
        var other = owner();
        var admin = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Admin", "ROLE_ADMIN"));
        plan(owner, Instant.parse("2026-10-30T10:00:00Z"), null);
        String othersPending = plan(other, Instant.parse("2026-11-01T10:00:00Z"), null);
        settings.update(false);
        // A submitter cannot approve their own plan, so it is not counted for them.
        mvc.perform(get("/api/schedules?state=PENDING&approvable=true").with(user(owner.getEmail())))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].runId").value(othersPending));
        // An admin may approve their own, so for an admin the filter leaves nothing out.
        plan(admin, Instant.parse("2026-11-02T10:00:00Z"), null);
        mvc.perform(get("/api/schedules?state=PENDING&approvable=true").with(user(admin.getEmail())))
            .andExpect(jsonPath("$.data.totalElements").value(3));
        // With self-approval allowed, nobody is left out.
        settings.update(true);
        mvc.perform(get("/api/schedules?state=PENDING&approvable=true").with(user(owner.getEmail())))
            .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test void summaryNamesTheLiveJobsTheirSavingsAndFirstStart() throws Exception {
        var owner = owner();
        var other = owner();
        String result = """
            {"runId":"r","status":"SCHEDULED","projectedSavingsUsd":1.5,"projectedSavingsPercent":12.5,
             "usedSeededFallbackPrices":true,"items":[
              {"jobId":1,"jobName":"Train","scheduledStart":"2026-11-02T03:00:00Z"},
              {"jobId":2,"jobName":"ETL","scheduledStart":"2026-11-02T01:00:00Z"},
              {"jobId":3,"jobName":"Removed","scheduledStart":"2026-11-01T00:00:00Z"},
              {"jobId":4,"jobName":"Unplaced","scheduledStart":null}]}""";
        var run = runs.save(OptimizerRun.builder().id(UUID.randomUUID().toString()).createdAt(Instant.parse("2026-11-01T10:00:00Z"))
            .solverName("test").solverVersion("1").solverConfig("{}").build());
        var plan = new OptimizationPlan(run, owner, result, List.of(1L, 2L, 3L, 4L), Instant.parse("2026-11-01T10:00:00Z"));
        plan.removeJobs(List.of(3L));
        plans.saveAndFlush(plan);
        mvc.perform(get("/api/schedules").with(user(owner.getEmail())))
            .andExpect(jsonPath("$.data.content[0].mine").value(true))
            .andExpect(jsonPath("$.data.content[0].jobNames.length()").value(3))
            .andExpect(jsonPath("$.data.content[0].jobNames[0]").value("Train"))
            .andExpect(jsonPath("$.data.content[0].jobNames[2]").value("Unplaced"))
            .andExpect(jsonPath("$.data.content[0].projectedSavingsUsd").value(1.5))
            .andExpect(jsonPath("$.data.content[0].projectedSavingsPercent").value(12.5))
            .andExpect(jsonPath("$.data.content[0].firstStart").value("2026-11-02T01:00:00Z"))
            .andExpect(jsonPath("$.data.content[0].usedSeededFallbackPrices").value(true));
        mvc.perform(get("/api/schedules").with(user(other.getEmail())))
            .andExpect(jsonPath("$.data.content[0].mine").value(false));
    }

    @Test void unreadableResultStillListsWithNoJobsOrSavings() throws Exception {
        var owner = owner();
        var run = runs.save(OptimizerRun.builder().id(UUID.randomUUID().toString()).createdAt(Instant.now())
            .solverName("test").solverVersion("1").solverConfig("{}").build());
        plans.saveAndFlush(new OptimizationPlan(run, owner, "not json", List.of(), Instant.now()));
        mvc.perform(get("/api/schedules").with(user(owner.getEmail())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].jobNames").isEmpty())
            .andExpect(jsonPath("$.data.content[0].projectedSavingsUsd").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].firstStart").doesNotExist());
    }

    @Test void rejectsUnknownStateFilter() throws Exception {
        var owner = owner();
        mvc.perform(get("/api/schedules?state=NOPE").with(user(owner.getEmail())))
            .andExpect(status().isBadRequest());
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
