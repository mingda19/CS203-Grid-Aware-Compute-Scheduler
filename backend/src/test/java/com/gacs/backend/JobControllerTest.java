package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.config.SecurityConfig;
import com.gacs.backend.controller.JobController;
import com.gacs.backend.dto.JobRequestDto;
import com.gacs.backend.dto.JobResponseDto;
import com.gacs.backend.exception.GlobalExceptionHandler;
import com.gacs.backend.model.JobPriority;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.service.JobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JobController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    private JobService jobService;

    private final LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
    private final LocalDateTime deadline = LocalDateTime.of(2026, 10, 21, 18, 0);

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("POST /api/jobs creates job and returns 201 with isExempt and originalStartTime")
    void createJob_Success() throws Exception {
        JobRequestDto request = JobRequestDto.builder()
                .name("LLM Fine-Tuning Run #42")
                .cpuHours(64.0)
                .gpuHours(16.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(12)
                .isExempt(true)
                .rawConstraints("Can wait up to 12 hours for low price valley")
                .build();

        JobResponseDto response = JobResponseDto.builder()
                .id(101L)
                .ownerEmail("operator@datacenter.io")
                .name("LLM Fine-Tuning Run #42")
                .cpuHours(64.0)
                .gpuHours(16.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(12)
                .isExempt(true)
                .rawConstraints("Can wait up to 12 hours for low price valley")
                .status("PENDING")
                .priority(JobPriority.NORMAL)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobService.createJob(any(JobRequestDto.class), eq("operator@datacenter.io"))).thenReturn(response);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job created successfully"))
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.name").value("LLM Fine-Tuning Run #42"))
                .andExpect(jsonPath("$.data.isExempt").value(true))
                .andExpect(jsonPath("$.data.originalStartTime").value("2026-10-20T10:00:00"))
                .andExpect(jsonPath("$.data.deadline").value("2026-10-21T18:00:00"))
                .andExpect(jsonPath("$.data.flexibilityWindowHours").value(12))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.priority").value("NORMAL"));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("POST /api/jobs when isExempt is omitted defaults to false")
    void createJob_WhenExemptOmitted_DefaultsToFalse() throws Exception {
        String payload = """
                {
                    "name": "Standard Job",
                    "cpuHours": 16.0,
                    "gpuHours": 0.0,
                    "originalStartTime": "2026-10-20T10:00:00",
                    "deadline": "2026-10-21T18:00:00",
                    "flexibilityWindowHours": 4
                }
                """;

        JobResponseDto response = JobResponseDto.builder()
                .id(102L)
                .ownerEmail("operator@datacenter.io")
                .name("Standard Job")
                .cpuHours(16.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(4)
                .isExempt(false)
                .status("PENDING")
                .priority(JobPriority.NORMAL)
                .build();

        when(jobService.createJob(any(JobRequestDto.class), eq("operator@datacenter.io"))).thenReturn(response);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(102))
                .andExpect(jsonPath("$.data.isExempt").value(false))
                .andExpect(jsonPath("$.data.priority").value("NORMAL"));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("POST /api/jobs with explicit priority returns 201 and priority value")
    void createJob_WithExplicitPriority_Returns201() throws Exception {
        String payload = """
                {
                    "name": "Critical Job",
                    "cpuHours": 32.0,
                    "gpuHours": 8.0,
                    "originalStartTime": "2026-10-20T10:00:00",
                    "deadline": "2026-10-21T18:00:00",
                    "flexibilityWindowHours": 0,
                    "priority": "CRITICAL"
                }
                """;

        JobResponseDto response = JobResponseDto.builder()
                .id(103L)
                .ownerEmail("operator@datacenter.io")
                .name("Critical Job")
                .cpuHours(32.0)
                .gpuHours(8.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(0)
                .isExempt(false)
                .status("PENDING")
                .priority(JobPriority.CRITICAL)
                .build();

        when(jobService.createJob(any(JobRequestDto.class), eq("operator@datacenter.io"))).thenReturn(response);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(103))
                .andExpect(jsonPath("$.data.priority").value("CRITICAL"));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("POST /api/jobs rejects unknown priority with 400 and clear error message")
    void createJob_InvalidPriority_Returns400() throws Exception {
        when(jobService.createJob(any(JobRequestDto.class), eq("operator@datacenter.io")))
                .thenThrow(new IllegalArgumentException("Invalid priority: 'URGENT'. Allowed priorities: CRITICAL, HIGH, NORMAL, LOW"));

        String payload = """
                {
                    "name": "Invalid Job",
                    "cpuHours": 10.0,
                    "gpuHours": 0.0,
                    "originalStartTime": "2026-10-20T10:00:00",
                    "deadline": "2026-10-21T18:00:00",
                    "flexibilityWindowHours": 0,
                    "priority": "URGENT"
                }
                """;

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid priority: 'URGENT'. Allowed priorities: CRITICAL, HIGH, NORMAL, LOW"));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("POST /api/jobs returns 400 when validation fails")
    void createJob_ValidationError() throws Exception {
        when(jobService.createJob(any(JobRequestDto.class), eq("operator@datacenter.io")))
                .thenThrow(new IllegalArgumentException("deadline must be greater than or equal to originalStartTime"));

        JobRequestDto request = JobRequestDto.builder()
                .name("Invalid Job")
                .cpuHours(10.0)
                .originalStartTime(deadline)
                .deadline(start)
                .build();

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("deadline must be greater than or equal to originalStartTime"));
    }

    @Test
    @DisplayName("POST /api/jobs returns 403 when unauthenticated")
    void createJob_Unauthenticated() throws Exception {
        JobRequestDto request = JobRequestDto.builder()
                .name("Unauthenticated Job")
                .cpuHours(10.0)
                .originalStartTime(start)
                .deadline(deadline)
                .build();

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("GET /api/jobs returns 200 with list of jobs")
    void listJobs_Success() throws Exception {
        JobResponseDto j1 = JobResponseDto.builder()
                .id(1L)
                .name("Job 1")
                .originalStartTime(start)
                .deadline(deadline)
                .isExempt(false)
                .build();

        JobResponseDto j2 = JobResponseDto.builder()
                .id(2L)
                .name("Job 2")
                .originalStartTime(start.plusHours(2))
                .deadline(deadline.plusHours(2))
                .isExempt(true)
                .build();

        when(jobService.getJobsByOwner("operator@datacenter.io")).thenReturn(List.of(j1, j2));

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("Job 1"))
                .andExpect(jsonPath("$.data[0].isExempt").value(false))
                .andExpect(jsonPath("$.data[1].name").value("Job 2"))
                .andExpect(jsonPath("$.data[1].isExempt").value(true));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("GET /api/jobs/{id} returns 200 when job exists")
    void getJob_Success() throws Exception {
        JobResponseDto job = JobResponseDto.builder()
                .id(101L)
                .name("Existing Job")
                .cpuHours(16.0)
                .gpuHours(4.0)
                .originalStartTime(start)
                .deadline(deadline)
                .isExempt(true)
                .build();

        when(jobService.getJobById(101L, "operator@datacenter.io")).thenReturn(job);

        mockMvc.perform(get("/api/jobs/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.name").value("Existing Job"))
                .andExpect(jsonPath("$.data.isExempt").value(true));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("GET /api/jobs/{id} returns 404 when job does not exist or wrong tenant")
    void getJob_NotFound() throws Exception {
        when(jobService.getJobById(999L, "operator@datacenter.io"))
                .thenThrow(new ResourceNotFoundException("Job not found with id: 999"));

        mockMvc.perform(get("/api/jobs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Job not found with id: 999"));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("PUT /api/jobs/{id} returns 200 and updates isExempt and times")
    void updateJob_Success() throws Exception {
        JobRequestDto updateRequest = JobRequestDto.builder()
                .name("Updated Title")
                .cpuHours(32.0)
                .gpuHours(8.0)
                .originalStartTime(start.plusHours(1))
                .deadline(deadline.plusHours(1))
                .flexibilityWindowHours(8)
                .isExempt(true)
                .rawConstraints("Updated constraints")
                .status("SCHEDULED")
                .build();

        JobResponseDto updatedResponse = JobResponseDto.builder()
                .id(101L)
                .ownerEmail("operator@datacenter.io")
                .name("Updated Title")
                .cpuHours(32.0)
                .gpuHours(8.0)
                .originalStartTime(start.plusHours(1))
                .deadline(deadline.plusHours(1))
                .flexibilityWindowHours(8)
                .isExempt(true)
                .rawConstraints("Updated constraints")
                .status("SCHEDULED")
                .build();

        when(jobService.updateJob(eq(101L), any(JobRequestDto.class), eq("operator@datacenter.io")))
                .thenReturn(updatedResponse);

        mockMvc.perform(put("/api/jobs/101")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job updated successfully"))
                .andExpect(jsonPath("$.data.name").value("Updated Title"))
                .andExpect(jsonPath("$.data.isExempt").value(true))
                .andExpect(jsonPath("$.data.flexibilityWindowHours").value(8))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("PUT /api/jobs/{id} returns 200 and toggles isExempt from true to false")
    void updateJob_ToggleExemptToFalse() throws Exception {
        JobRequestDto updateRequest = JobRequestDto.builder()
                .name("Unmark Exemption")
                .cpuHours(16.0)
                .gpuHours(4.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(12)
                .isExempt(false)
                .status("PENDING")
                .build();

        JobResponseDto updatedResponse = JobResponseDto.builder()
                .id(101L)
                .ownerEmail("operator@datacenter.io")
                .name("Unmark Exemption")
                .cpuHours(16.0)
                .gpuHours(4.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(12)
                .isExempt(false)
                .status("PENDING")
                .build();

        when(jobService.updateJob(eq(101L), any(JobRequestDto.class), eq("operator@datacenter.io")))
                .thenReturn(updatedResponse);

        mockMvc.perform(put("/api/jobs/101")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isExempt").value(false))
                .andExpect(jsonPath("$.data.flexibilityWindowHours").value(12));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("PUT /api/jobs/{id} returns 404 when job not found")
    void updateJob_NotFound() throws Exception {
        JobRequestDto updateRequest = JobRequestDto.builder()
                .name("Missing")
                .cpuHours(10.0)
                .originalStartTime(start)
                .deadline(deadline)
                .build();

        when(jobService.updateJob(eq(404L), any(JobRequestDto.class), eq("operator@datacenter.io")))
                .thenThrow(new ResourceNotFoundException("Job not found with id: 404"));

        mockMvc.perform(put("/api/jobs/404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("DELETE /api/jobs/{id} returns 200 on successful deletion")
    void deleteJob_Success() throws Exception {
        doNothing().when(jobService).deleteJob(101L, "operator@datacenter.io");

        mockMvc.perform(delete("/api/jobs/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job deleted successfully"));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("DELETE /api/jobs/{id} returns 404 when job does not exist")
    void deleteJob_NotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Job not found with id: 999"))
                .when(jobService).deleteJob(999L, "operator@datacenter.io");

        mockMvc.perform(delete("/api/jobs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
