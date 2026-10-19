package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gacs.backend.dto.JobRequestDto;
import com.gacs.backend.model.Job;
import com.gacs.backend.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end API Integration tests for verifying acceptance criteria AC4 of GRID-86 / GRID-24:
 * "The exempt flag (default off) is defined in the job schema (GRID-112) and exposed by the CRUD endpoints (GRID-113).
 * A test confirms the flag is stored, returned by the job API and editable through create and edit."
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JobExemptionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobRepository jobRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final String operator = "operator@datacenter.io";
    private final String otherOperator = "other-tenant@datacenter.io";

    private final LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
    private final LocalDateTime deadline = LocalDateTime.of(2026, 10, 21, 18, 0);

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("AC4 Verification: Job created without isExempt defaults to false (default off) in both API response and database")
    void createJob_WhenExemptFlagOmitted_DefaultsToOff() throws Exception {
        // Omitting isExempt completely in request body
        String jsonPayload = """
                {
                    "name": "Default Exemption Workload",
                    "cpuHours": 32.0,
                    "gpuHours": 8.0,
                    "originalStartTime": "2026-10-20T10:00:00",
                    "deadline": "2026-10-21T18:00:00",
                    "flexibilityWindowHours": 6
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.name").value("Default Exemption Workload"))
                .andExpect(jsonPath("$.data.isExempt").value(false))
                .andReturn();

        Long createdId = extractIdFromResponse(result);

        // Verify stored state directly in the database
        Job persisted = jobRepository.findById(createdId).orElseThrow();
        assertThat(persisted.isExempt())
                .as("Exempt flag must default to false (off) in the database schema")
                .isFalse();
        assertThat(persisted.getOwnerEmail()).isEqualTo(operator);
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("AC4 Verification: Job created with isExempt=true is stored as true and returned by create response")
    void createJob_WithExemptTrue_StoredAndReturned() throws Exception {
        JobRequestDto request = JobRequestDto.builder()
                .name("Critical Non-Negotiable Job")
                .cpuHours(64.0)
                .gpuHours(16.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(0)
                .isExempt(true)
                .rawConstraints("Must not be rescheduled under any circumstance")
                .build();

        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isExempt").value(true))
                .andReturn();

        Long createdId = extractIdFromResponse(result);

        Job persisted = jobRepository.findById(createdId).orElseThrow();
        assertThat(persisted.isExempt())
                .as("Exempt flag must be persisted as true in the database")
                .isTrue();
        assertThat(persisted.getRawConstraints()).isEqualTo("Must not be rescheduled under any circumstance");
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("AC4 Verification: Job API returns stored exemption flag via GET /api/jobs/{id} and GET /api/jobs")
    void getJob_ReturnsStoredExemptionFlag() throws Exception {
        // Persist one exempt job and one non-exempt job
        Job nonExempt = jobRepository.save(Job.builder()
                .ownerEmail(operator)
                .name("Flexible Batch Job")
                .cpuHours(16.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .isExempt(false)
                .build());

        Job exempt = jobRepository.save(Job.builder()
                .ownerEmail(operator)
                .name("Emergency Simulation Run")
                .cpuHours(32.0)
                .gpuHours(8.0)
                .originalStartTime(start.plusHours(2))
                .deadline(deadline.plusHours(2))
                .isExempt(true)
                .build());

        // 1. GET /api/jobs/{id} for non-exempt job
        mockMvc.perform(get("/api/jobs/" + nonExempt.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(nonExempt.getId()))
                .andExpect(jsonPath("$.data.isExempt").value(false));

        // 2. GET /api/jobs/{id} for exempt job
        mockMvc.perform(get("/api/jobs/" + exempt.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(exempt.getId()))
                .andExpect(jsonPath("$.data.isExempt").value(true));

        // 3. GET /api/jobs list endpoint
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(nonExempt.getId()))
                .andExpect(jsonPath("$.data[0].isExempt").value(false))
                .andExpect(jsonPath("$.data[1].id").value(exempt.getId()))
                .andExpect(jsonPath("$.data[1].isExempt").value(true));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("AC4 Verification: Job exemption flag is editable through PUT /api/jobs/{id} (toggle false -> true)")
    void updateJob_ToggleExemptFalseToTrue_StoredAndReturned() throws Exception {
        // Initial state: isExempt = false
        Job job = jobRepository.save(Job.builder()
                .ownerEmail(operator)
                .name("Workload to be Promoted")
                .cpuHours(20.0)
                .gpuHours(4.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(8)
                .isExempt(false)
                .status("PENDING")
                .build());

        Long jobId = job.getId();

        // Update: mark as exempt
        JobRequestDto editRequest = JobRequestDto.builder()
                .name("Workload Promoted to Exempt")
                .cpuHours(20.0)
                .gpuHours(4.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(0)
                .isExempt(true)
                .rawConstraints("Promoted to exempt by cluster administrator")
                .status("SCHEDULED")
                .build();

        mockMvc.perform(put("/api/jobs/" + jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(jobId))
                .andExpect(jsonPath("$.data.name").value("Workload Promoted to Exempt"))
                .andExpect(jsonPath("$.data.isExempt").value(true))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));

        // Verify stored in database
        Job updated = jobRepository.findById(jobId).orElseThrow();
        assertThat(updated.isExempt())
                .as("Exempt flag must be updated to true in database")
                .isTrue();
        assertThat(updated.getName()).isEqualTo("Workload Promoted to Exempt");
        assertThat(updated.getRawConstraints()).isEqualTo("Promoted to exempt by cluster administrator");
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("AC4 Verification: Job exemption flag is editable through PUT /api/jobs/{id} (toggle true -> false)")
    void updateJob_ToggleExemptTrueToFalse_StoredAndReturned() throws Exception {
        // Initial state: isExempt = true
        Job job = jobRepository.save(Job.builder()
                .ownerEmail(operator)
                .name("Previously Exempt Workload")
                .cpuHours(40.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(0)
                .isExempt(true)
                .status("SCHEDULED")
                .build());

        Long jobId = job.getId();

        // Update: demote to flexible (isExempt = false)
        JobRequestDto editRequest = JobRequestDto.builder()
                .name("Flexible Workload")
                .cpuHours(40.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(12)
                .isExempt(false)
                .rawConstraints("Exemption removed; can shift into price troughs")
                .status("PENDING")
                .build();

        mockMvc.perform(put("/api/jobs/" + jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(jobId))
                .andExpect(jsonPath("$.data.name").value("Flexible Workload"))
                .andExpect(jsonPath("$.data.isExempt").value(false))
                .andExpect(jsonPath("$.data.flexibilityWindowHours").value(12));

        // Verify stored in database
        Job updated = jobRepository.findById(jobId).orElseThrow();
        assertThat(updated.isExempt())
                .as("Exempt flag must be updated to false in database")
                .isFalse();
        assertThat(updated.getFlexibilityWindowHours()).isEqualTo(12);
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("AC4 Verification: JSON aliases 'exempt' and 'is_exempt' deserialize and store correctly")
    void createJob_SupportsJsonAliases() throws Exception {
        // 1. Using alias "exempt"
        String payloadWithExempt = """
                {
                    "name": "Alias Test Job 1",
                    "cpuHours": 10.0,
                    "gpuHours": 2.0,
                    "originalStartTime": "2026-10-20T10:00:00",
                    "deadline": "2026-10-21T18:00:00",
                    "flexibilityWindowHours": 0,
                    "exempt": true
                }
                """;

        MvcResult res1 = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWithExempt))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isExempt").value(true))
                .andReturn();

        Long id1 = extractIdFromResponse(res1);
        assertThat(jobRepository.findById(id1).orElseThrow().isExempt()).isTrue();

        // 2. Using alias "is_exempt"
        String payloadWithSnakeCase = """
                {
                    "name": "Alias Test Job 2",
                    "cpuHours": 10.0,
                    "gpuHours": 2.0,
                    "originalStartTime": "2026-10-20T10:00:00",
                    "deadline": "2026-10-21T18:00:00",
                    "flexibilityWindowHours": 4,
                    "is_exempt": true
                }
                """;

        MvcResult res2 = mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWithSnakeCase))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isExempt").value(true))
                .andReturn();

        Long id2 = extractIdFromResponse(res2);
        assertThat(jobRepository.findById(id2).orElseThrow().isExempt()).isTrue();
    }

    @Test
    @WithMockUser(username = "other-tenant@datacenter.io")
    @DisplayName("AC4 Verification: Tenancy isolation prevents another operator from viewing or modifying exemption")
    void tenancyIsolation_ProtectsExemptionStatus() throws Exception {
        // Job owned by operator@datacenter.io
        Job ownedByFirst = jobRepository.save(Job.builder()
                .ownerEmail(operator)
                .name("Tenant 1 Job")
                .cpuHours(50.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .isExempt(true)
                .build());

        Long jobId = ownedByFirst.getId();

        // Request executed as other-tenant@datacenter.io
        // 1. GET returns 404
        mockMvc.perform(get("/api/jobs/" + jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));

        // 2. PUT attempt to toggle exemption returns 404
        JobRequestDto unauthorizedEdit = JobRequestDto.builder()
                .name("Unauthorized Edit")
                .cpuHours(50.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .isExempt(false)
                .build();

        mockMvc.perform(put("/api/jobs/" + jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unauthorizedEdit)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));

        // Verify stored state in database is unmodified
        Job unmolested = jobRepository.findById(jobId).orElseThrow();
        assertThat(unmolested.isExempt())
                .as("Exemption state must remain true and protected from other tenants")
                .isTrue();
    }

    private Long extractIdFromResponse(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        var node = objectMapper.readTree(content);
        return node.path("data").path("id").asLong();
    }
}
