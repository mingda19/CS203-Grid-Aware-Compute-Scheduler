package com.gacs.backend;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.JobPriority;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.AuditEventRepository;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.service.ApprovedScheduleCsvSerializer;
import com.gacs.backend.service.ApprovedScheduleJsonSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.gacs.backend.support.MutableClock;
import com.gacs.backend.support.MutableClockConfig;
import org.springframework.context.annotation.Import;

/**
 * GRID-135 AC3 / AC4.3 — the export approval gate, end to end: real jobs, optimiser runs and decisions
 * (docs/api-contract.md section 6.6).
 */
// This class dates its fixtures from the real clock rather than from fixed constants, so it must not
// be pinned to a constant like the other integration tests. It takes a movable clock started at the
// real instant instead, so elapsedPlan_Stale can let time pass.
@Import(MutableClockConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleExportIntegrationTest {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Autowired MockMvc mvc;
    @Autowired com.gacs.backend.service.AppSettingsService appSettings;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired OptimizationPlanRepository plans;
    @Autowired OptimizerRunRepository runs;
    @Autowired AuditEventRepository auditEvents;
    @Autowired ApprovedScheduleCsvSerializer csvSerializer;
    @Autowired ApprovedScheduleJsonSerializer jsonSerializer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<String> createdRuns = new ArrayList<>();
    private final List<Long> createdJobs = new ArrayList<>();
    private User owner;

    /** Two days ahead, on the hour, so plans are not stale by time. */
    private final LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).plusDays(2);

    @BeforeEach
    void setup() {
        appSettings.update(true);
        owner = users.save(new User(UUID.randomUUID() + "@test.io", "test-password", "Operator", "ROLE_USER"));
    }

    @AfterEach
    void cleanup() {
        clock.set(Instant.now());
        // Audit records remain append-only; each test uses fresh run IDs.
        for (String runId : createdRuns) {
            plans.deleteById(runId);
            runs.deleteById(runId);
        }
        jobs.deleteAllById(createdJobs);
        users.deleteById(owner.getId());
    }

    /** A 4 CPU-hour job that may start at {@code from} or an hour later and must finish within 3 hours. */
    private Job job(String name, LocalDateTime from) {
        Job saved = jobs.save(Job.builder()
                .ownerEmail(owner.getEmail()).name(name).cpuHours(4.0).gpuHours(0.0)
                .originalStartTime(from).deadline(from.plusHours(3)).flexibilityWindowHours(1).isExempt(false)
                .build());
        createdJobs.add(saved.getId());
        return saved;
    }

    /**
     * Optimises the jobs against prices that make the second hour cheapest, so each job is moved an hour later
     * than its original start.
     */
    private String optimize(LocalDateTime from, Job... jobList) throws Exception {
        List<Map<String, Object>> jobsJson = new ArrayList<>();
        for (Job j : jobList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.getId());
            m.put("name", j.getName());
            m.put("cpuHours", j.getCpuHours());
            m.put("gpuHours", j.getGpuHours());
            // Like the app, which truncates the start to the hour before optimising.
            m.put("originalStartTime", UTC.format(j.getOriginalStartTime().truncatedTo(ChronoUnit.HOURS)));
            m.put("deadline", UTC.format(j.getDeadline()));
            m.put("flexibilityWindowHours", j.getFlexibilityWindowHours());
            m.put("isExempt", j.isExempt());
            jobsJson.add(m);
        }
        List<Map<String, Object>> forecast = List.of(
                Map.of("intervalStartUtc", UTC.format(from), "predictedPrice", 100),
                Map.of("intervalStartUtc", UTC.format(from.plusHours(1)), "predictedPrice", 10),
                Map.of("intervalStartUtc", UTC.format(from.plusHours(2)), "predictedPrice", 50));
        String response = mvc.perform(post("/api/schedules/optimize").with(user(owner.getEmail()))
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("jobs", jobsJson, "forecast", forecast))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = json.readTree(response).path("data").path("runId").asText();
        createdRuns.add(runId);
        return runId;
    }

    private void decide(String runId, String action) throws Exception {
        mvc.perform(post("/api/schedules/" + runId + "/" + action).with(user(owner.getEmail()))
                        .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions export(String runId, String extension) throws Exception {
        return mvc.perform(get("/api/schedules/" + runId + "/export." + extension).with(user(owner.getEmail())));
    }

    private void expectRejected(String runId, int status, String reason, String messagePart) throws Exception {
        for (String extension : List.of("csv", "json")) {
            export(runId, extension)
                    .andExpect(status().is(status))
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(header().doesNotExist("Content-Disposition"))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.reason").value(reason))
                    .andExpect(jsonPath("$.message").value(containsString(messagePart)));
        }
    }

    private OptimizeResponseDto storedPlan(String runId) throws Exception {
        return json.readValue(plans.findById(runId).orElseThrow().getResultJson(), OptimizeResponseDto.class);
    }

    @Test
    @DisplayName("AC4.3b — an approved plan downloads as CSV and JSON attachments named schedule-run-<id>")
    void approvedPlan_DownloadsAsAttachments() throws Exception {
        Job train = job("Train", start);
        String runId = optimize(start, train);
        decide(runId, "approve");
        OptimizeResponseDto plan = storedPlan(runId);

        String csv = export(runId, "csv")
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"schedule-run-" + runId + ".csv\""))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).isEqualTo(csvSerializer.serialize(plan));
        assertThat(csv).contains(runId + "," + train.getId() + ",Train,OPTIMISED," + UTC.format(start.plusHours(1)));

        String body = export(runId, "json")
                .andExpect(status().isOk())
                .andExpect(content().contentType(APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"schedule-run-" + runId + ".json\""))
                .andReturn().getResponse().getContentAsString();
        AuditEvent approval = auditEvents.findFirstByRunIdAndActionTypeInOrderByIdDesc(runId,
                EnumSet.of(AuditActionType.APPROVE, AuditActionType.REJECT, AuditActionType.REVISE)).orElseThrow();
        assertThat(body).isEqualTo(jsonSerializer.serialize(plan, runs.findById(runId).orElseThrow(), approval));
        assertThat(body)
                .contains("\"solver\":{\"name\":\"HybridAllocationSolver\",\"version\":\"1\",\"config\":{")
                .contains("\"approval\":{\"decisionId\":" + approval.getId() + ",\"approvedBy\":\"" + owner.getEmail() + "\"");
    }

    @Test
    @DisplayName("AC4.3a — the browser app can read the download filename across origins")
    void crossOriginDownload_ExposesContentDisposition() throws Exception {
        String runId = optimize(start, job("Train", start));
        decide(runId, "approve");

        mvc.perform(get("/api/schedules/" + runId + "/export.csv").with(user(owner.getEmail()))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Content-Disposition")));
    }

    @Test
    @DisplayName("AC4.3 — pending, rejected and revision-requested plans are all refused with 403")
    void unapprovedPlans_Forbidden() throws Exception {
        // A run now holds one decision for life, so each state needs its own run rather than being
        // walked through on a single one. Only an approved run can change state again, by revocation.
        String pending = optimize(start, job("Pending", start));
        expectRejected(pending, 403, "NOT_APPROVED", "is PENDING, not APPROVED");

        String rejected = optimize(start, job("Rejected", start));
        decide(rejected, "reject");
        expectRejected(rejected, 403, "NOT_APPROVED", "is REJECTED");

        String revised = optimize(start, job("Revised", start));
        decide(revised, "revise");
        expectRejected(revised, 403, "NOT_APPROVED", "is REVISION_REQUESTED");

        String approved = optimize(start, job("Approved", start));
        decide(approved, "approve");
        export(approved, "csv").andExpect(status().isOk());
    }

    @Test
    @DisplayName("A revoked approval stops being exportable")
    void revokedPlan_Forbidden() throws Exception {
        String runId = optimize(start, job("Train", start));
        decide(runId, "approve");
        export(runId, "csv").andExpect(status().isOk());

        decide(runId, "revoke");
        expectRejected(runId, 403, "NOT_APPROVED", "is REJECTED");
    }

    @Test
    @DisplayName("Missing and unowned plans look the same (404); bad IDs, tokens and users are refused")
    void missingUnownedAndUnauthenticated() throws Exception {
        String runId = optimize(start, job("Train", start));
        decide(runId, "approve");

        User other = users.save(new User(UUID.randomUUID() + "@test.io", "password", "Other", "ROLE_ADMIN"));
        try {
            String hidden = mvc.perform(get("/api/schedules/" + runId + "/export.csv").with(user(other.getEmail()).roles("ADMIN")))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            String missing = mvc.perform(get("/api/schedules/" + UUID.randomUUID() + "/export.csv").with(user(other.getEmail()).roles("ADMIN")))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(hidden).isEqualTo(missing);
        } finally {
            users.deleteById(other.getId());
        }
        mvc.perform(get("/api/schedules/not-a-uuid/export.csv").with(user(owner.getEmail())))
                .andExpect(status().isBadRequest());
        // Auth failures carry no gate reason, which is how clients tell them apart from NOT_APPROVED.
        mvc.perform(get("/api/schedules/" + runId + "/export.csv"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("reason"))));
        mvc.perform(get("/api/schedules/" + runId + "/export.json").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/schedules/" + runId + "/export.json").with(user("missing@test.io")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC4.3 — a newer plan for the same job replaces the older waiting one, which is stale (409)")
    void newerPlanOfSameJob_Stale() throws Exception {
        Job shared = job("Shared", start);
        String older = optimize(start, shared);

        String unrelated = optimize(start, job("Unrelated", start));
        decide(unrelated, "approve");
        expectRejected(older, 403, "NOT_APPROVED", "is PENDING, not APPROVED"); // an unrelated approval changes nothing

        String newer = optimize(start, shared);
        expectRejected(older, 409, "STALE", "Replaced by run " + newer);
        decide(newer, "approve");
        export(newer, "csv").andExpect(status().isOk());

        decide(newer, "revoke");
        expectRejected(older, 409, "STALE", "Replaced by run " + newer);
    }

    @Test
    @DisplayName("AC4.3 — a plan whose slots have all ended is stale (409)")
    void elapsedPlan_Stale() throws Exception {
        // Make a real, feasible plan, then let time pass beyond its last slot. Back-dating the job
        // instead no longer reaches this state: the optimiser will not place work in elapsed hours, so
        // a past-dated job comes back INFEASIBLE and is rejected as NOT_APPROVED long before the
        // staleness check. Moving the clock is also the only version of this that matches production,
        // where a plan goes stale because it was approved and then sat too long.
        String runId = optimize(start, job("Train", start));
        clock.set(start.plusHours(3).toInstant(ZoneOffset.UTC));
        mvc.perform(post("/api/schedules/" + runId + "/approve").with(user(owner.getEmail()))
            .contentType(APPLICATION_JSON).content("{\"comment\":\"reviewed\"}"))
            .andExpect(status().isConflict());
        expectRejected(runId, 409, "STALE", "all of its slots ended by " + UTC.format(start.plusHours(2)));
    }

    @Test
    @DisplayName("AC4.3 — a stored plan changed after approval is invalidated (409)")
    void tamperedPlan_Invalidated() throws Exception {
        String runId = optimize(start, job("Train", start));
        decide(runId, "approve");
        String stored = plans.findById(runId).orElseThrow().getResultJson();

        jdbc.update("update optimization_plans set result_json = ? where run_id = ?",
                stored.replace("\"jobName\":\"Train\"", "\"jobName\":\"Tampered\""), runId);
        assertThat(plans.findById(runId).orElseThrow().getResultJson()).isNotEqualTo(stored);

        expectRejected(runId, 409, "INVALIDATED", "no longer matches the hash recorded when it was approved");
    }

    @Test
    @DisplayName("AC4.3 — an approval record that does not match the plan invalidates the export (409)")
    void mismatchedApprovalRecord_Invalidated() throws Exception {
        String forged = optimize(start, job("Forged", start));
        decide(forged, "approve");
        auditEvents.save(AuditEvent.builder().actor(owner.getEmail()).actionType(AuditActionType.APPROVE)
                .timestamp(Instant.now()).runId(forged).scheduleHash("0".repeat(64))
                .operatorRationale("approval of a different schedule").build());
        expectRejected(forged, 409, "INVALIDATED", "no longer matches the hash");

        String flipped = optimize(start, job("Flipped", start));
        decide(flipped, "reject");
        jdbc.update("update optimization_plans set review_state = 'APPROVED' where run_id = ?", flipped);
        expectRejected(flipped, 409, "INVALIDATED", "latest recorded decision is REJECT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cpu-hours", "start-moved", "deadline-earlier", "made-exempt", "deleted",
            "deadline-later", "more-flexible"})
    @DisplayName("AC4.3 — editing or deleting a scheduled job after approval invalidates the export (409)")
    void changedJob_Invalidated(String change) throws Exception {
        Job job = job("Train", start);
        String runId = optimize(start, job);
        decide(runId, "approve");
        export(runId, "csv").andExpect(status().isOk());

        String expected = switch (change) {
            case "cpu-hours" -> {
                job.setCpuHours(8.0);
                yield "Scheduling inputs changed";
            }
            case "start-moved" -> {
                job.setOriginalStartTime(start.plusHours(2));
                yield "Scheduling inputs changed";
            }
            case "deadline-earlier" -> {
                job.setDeadline(start.plusHours(1).plusMinutes(30));
                yield "Scheduling inputs changed";
            }
            case "made-exempt" -> {
                job.setExempt(true);
                yield "Scheduling inputs changed";
            }
            // GRID-141: the plan still fits, but the job is not what the optimiser was given.
            case "deadline-later" -> {
                job.setDeadline(start.plusHours(10));
                yield "Scheduling inputs changed";
            }
            case "more-flexible" -> {
                job.setFlexibilityWindowHours(4);
                yield "Scheduling inputs changed";
            }
            default -> "is no longer in your jobs";
        };
        if (change.equals("deleted")) {
            jobs.deleteById(job.getId());
        } else {
            jobs.save(job);
        }

        expectRejected(runId, 409, "INVALIDATED", expected);
    }

    @Test
    @DisplayName("GRID-141 — a job start with minutes, truncated to the hour when optimising, still exports")
    void startWithMinutes_StillExportable() throws Exception {
        String runId = optimize(start, job("Train", start.plusMinutes(30)));
        decide(runId, "approve");

        export(runId, "csv").andExpect(status().isOk());
    }

    @Test
    @DisplayName("Edits to the job name and non-cancelled status keep it exportable")
    void harmlessJobEdits_StillExportable() throws Exception {
        Job job = job("Train", start);
        String runId = optimize(start, job);
        decide(runId, "approve");

        job.setName("Train (renamed)");
        job.setStatus("SCHEDULED");
        jobs.save(job);

        export(runId, "csv").andExpect(status().isOk());
        export(runId, "json").andExpect(status().isOk());
    }
}
