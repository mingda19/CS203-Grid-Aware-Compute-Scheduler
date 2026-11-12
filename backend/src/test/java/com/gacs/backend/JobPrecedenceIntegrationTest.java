package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.*;
import com.gacs.backend.model.*;
import com.gacs.backend.repository.*;
import com.gacs.backend.service.*;
import com.gacs.backend.service.allocation.*;
import com.gacs.backend.service.constraintparse.*;
import com.gacs.backend.support.FixedClockConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
@Transactional
class JobPrecedenceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JobRepository jobs;
    @Autowired UserRepository users;
    @Autowired EntityManager entities;
    @Autowired ScheduleInputs inputs;
    @MockitoBean ConstraintClassifier classifier;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);

    @Test void parseConfirmSaveReloadAndSolvePrecedence() throws Exception {
        String owner = UUID.randomUUID() + "@test.io";
        users.save(new User(owner, "password", "Owner", "ROLE_USER"));
        Job target = jobs.save(Job.builder().ownerEmail(owner).name("DeepSeek-R1 Distillation Batch")
                .cpuHours(1.0).originalStartTime(start).deadline(start.plusHours(4)).flexibilityWindowHours(3).build());
        long targetId = target.getId();
        String text = "this job must finish before DeepSeek-R1 Distillation Batch starts";
        when(classifier.classify(eq(text), anySet(), anyList(), isNull())).thenReturn(List.of(
                new ClassifiedConstraint(ConstraintType.PRECEDENCE, .94, text, 0, text.length(), target.getName())));
        var parseRequest = new ConstraintParseRequestDto(text,
                new ConstraintParseRequestDto.Draft(start, start.plusHours(4), 3, "NORMAL", false), "America/Chicago");
        var parsedBody = mvc.perform(post("/api/jobs/parse-constraints").with(user(owner))
                        .contentType(APPLICATION_JSON).content(json.writeValueAsString(parseRequest)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PARSED"))
                .andExpect(jsonPath("$.data.unsupported").isEmpty())
                .andExpect(jsonPath("$.data.suggestions[0].referencedJobId").value(targetId))
                .andReturn().getResponse().getContentAsString();
        String parsedAt = json.readTree(parsedBody).path("data").path("parsedAt").asText();
        var payload = JobRequestDto.builder().name("Training").cpuHours(1.0).originalStartTime(start)
                .deadline(start.plusHours(4)).flexibilityWindowHours(3).rawConstraints(text)
                .parsedAt(LocalDateTime.parse(parsedAt)).confirmationOutcome(ConfirmationOutcome.CONFIRMED)
                .pairConstraints(List.of(new JobConstraint(ConstraintType.PRECEDENCE, targetId))).build();
        var created = mvc.perform(post("/api/jobs").with(user(owner)).contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(payload))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long sourceId = json.readTree(created).path("data").path("id").asLong();
        entities.flush();
        entities.clear();
        mvc.perform(get("/api/jobs/" + sourceId).with(user(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pairConstraints[0].type").value("PRECEDENCE"))
                .andExpect(jsonPath("$.data.pairConstraints[0].referencedJobId").value(targetId));
        var submitted = List.of(sourceId, targetId).stream().map(id -> new OptimizeRequestDto.JobInput(
                id, "job " + id, 1.0, 0.0, start, start.plusHours(4), 3, false, "NORMAL")).toList();
        var forecast = java.util.stream.IntStream.range(0, 4).mapToObj(i ->
                new OptimizeRequestDto.ForecastPointInput(start.plusHours(i), 10.0 + i)).toList();
        var rawRequest = new OptimizeRequestDto(submitted, null, null, null, forecast, null);
        // The scheduling caller sends no pair rules. The server must load and enforce the saved rule.
        String scheduled = mvc.perform(post("/api/schedules/optimize").with(user(owner)).contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(rawRequest))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andReturn().getResponse().getContentAsString();
        var items = json.readTree(scheduled).path("data").path("items");
        assertThat(items.get(0).path("jobId").asLong()).isEqualTo(sourceId);
        assertThat(items.get(1).path("jobId").asLong()).isEqualTo(targetId);
        assertThat(java.time.Instant.parse(items.get(1).path("scheduledStart").asText()))
                .isAfterOrEqualTo(java.time.Instant.parse(items.get(0).path("scheduledEnd").asText()));
        var request = inputs.withStoredConstraints(rawRequest, owner);

        // Removing the relationship is explicit and increments the scheduling revision.
        var provenance = inputs.capture(request, owner, Map.of());
        var oldPlan = new OptimizationPlan(null, users.findByEmail(owner).orElseThrow(), "{}",
                List.of(sourceId, targetId), FixedClockConfig.INSTANT);
        oldPlan.recordInputs(provenance);
        assertThat(inputs.mismatch(oldPlan, owner)).isNull();
        long revision = jobs.findById(sourceId).orElseThrow().getSchedulingRevision();
        payload.setPairConstraints(List.of());
        mvc.perform(put("/api/jobs/" + sourceId).with(user(owner)).contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(payload))).andExpect(status().isOk());
        assertThat(jobs.findById(sourceId).orElseThrow().getSchedulingRevision()).isGreaterThan(revision);
        assertThat(inputs.mismatch(oldPlan, owner)).contains("Scheduling inputs changed");
    }
}
