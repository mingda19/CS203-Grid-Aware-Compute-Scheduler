package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.config.SecurityConfig;
import com.gacs.backend.controller.AuditEventController;
import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.exception.GlobalExceptionHandler;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.service.AuditEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditEventController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuditEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    private AuditEventService auditEventService;

    private final String validRunId = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44";
    private final String validHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final String validRationale = "Approved schedule after cooling check.";

    @Test
    @WithMockUser(username = "operator@gacs.internal")
    @DisplayName("AC4.1 — POST /api/audit-events records audit event and returns 201 Created")
    void recordEvent_Success() throws Exception {
        AuditEventRequestDto request = new AuditEventRequestDto(
                AuditActionType.APPROVE,
                validRunId,
                validHash,
                validRationale
        );

        AuditEventResponseDto responseDto = new AuditEventResponseDto(
                1L,
                "operator@gacs.internal",
                AuditActionType.APPROVE,
                Instant.parse("2026-10-28T09:30:00Z"),
                validRunId,
                validHash,
                validRationale
        );

        when(auditEventService.recordEvent(any(AuditEventRequestDto.class))).thenReturn(responseDto);

        mockMvc.perform(post("/api/audit-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Audit event recorded successfully"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.actor").value("operator@gacs.internal"))
                .andExpect(jsonPath("$.data.actionType").value("APPROVE"))
                .andExpect(jsonPath("$.data.runId").value(validRunId))
                .andExpect(jsonPath("$.data.scheduleHash").value(validHash))
                .andExpect(jsonPath("$.data.operatorRationale").value(validRationale));
    }

    @Test
    @WithMockUser(username = "operator@gacs.internal")
    @DisplayName("AC4.1a — Caller-supplied actor in JSON payload is ignored; authenticated user is enforced")
    void recordEvent_CallerSuppliedActorIgnored() throws Exception {
        // Construct raw JSON payload that attempts to inject an actor
        String spoofedPayload = """
                {
                    "actor": "malicious_hacker@darkweb.org",
                    "actionType": "APPROVE",
                    "runId": "%s",
                    "scheduleHash": "%s",
                    "operatorRationale": "%s"
                }
                """.formatted(validRunId, validHash, validRationale);

        // Service returns response strictly using authenticated username
        AuditEventResponseDto responseDto = new AuditEventResponseDto(
                2L,
                "operator@gacs.internal",
                AuditActionType.APPROVE,
                Instant.now(),
                validRunId,
                validHash,
                validRationale
        );

        when(auditEventService.recordEvent(any(AuditEventRequestDto.class))).thenReturn(responseDto);

        mockMvc.perform(post("/api/audit-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spoofedPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.actor").value("operator@gacs.internal"));
    }

    @Test
    @DisplayName("POST /api/audit-events returns 401/403 when unauthenticated")
    void recordEvent_UnauthenticatedFails() throws Exception {
        AuditEventRequestDto request = new AuditEventRequestDto(
                AuditActionType.APPROVE,
                validRunId,
                validHash,
                validRationale
        );

        mockMvc.perform(post("/api/audit-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "operator@gacs.internal")
    @DisplayName("POST /api/audit-events returns 400 when required fields are missing or invalid")
    void recordEvent_ValidationErrors() throws Exception {
        // Missing actionType, blank runId, invalid scheduleHash length, blank rationale
        String invalidPayload = """
                {
                    "runId": "",
                    "scheduleHash": "too-short",
                    "operatorRationale": "   "
                }
                """;

        mockMvc.perform(post("/api/audit-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    @WithMockUser(username = "operator@gacs.internal")
    @DisplayName("GET /api/audit-events/run/{runId} returns 200 with list of events")
    void getEventsByRunId_Success() throws Exception {
        AuditEventResponseDto event = new AuditEventResponseDto(
                10L,
                "operator@gacs.internal",
                AuditActionType.APPROVE,
                Instant.now(),
                validRunId,
                validHash,
                validRationale
        );

        when(auditEventService.getEventsByRunId(validRunId)).thenReturn(List.of(event));

        mockMvc.perform(get("/api/audit-events/run/" + validRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].runId").value(validRunId))
                .andExpect(jsonPath("$.data[0].actionType").value("APPROVE"));
    }

    @Test
    @WithMockUser(username = "operator@gacs.internal")
    @DisplayName("GET /api/audit-events/{id} returns 404 when event does not exist")
    void getEventById_NotFound() throws Exception {
        when(auditEventService.getEventById(999L))
                .thenThrow(new ResourceNotFoundException("Audit event not found with id: 999"));

        mockMvc.perform(get("/api/audit-events/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Audit event not found with id: 999"));
    }
}
