package com.gacs.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.config.SecurityConfig;
import com.gacs.backend.controller.ConstraintParseController;
import com.gacs.backend.exception.GlobalExceptionHandler;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.service.JobService;
import com.gacs.backend.service.constraintparse.ConstraintParseService;
import com.gacs.backend.service.constraintparse.RuleBasedConstraintParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConstraintParseController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ConstraintParseService.class,
        RuleBasedConstraintParser.class, JobService.class, ConstraintParseControllerTest.FixedClock.class})
class ConstraintParseControllerTest {

    /** Monday 2026-11-02 14:00 in Singapore. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-02T06:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobRepository jobRepository;

    @MockitoBean
    private com.gacs.backend.service.ScheduleInputs scheduleInputs;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("POST /api/jobs/parse-constraints returns parsed fields, evidence and status")
    void parses() throws Exception {
        mockMvc.perform(post("/api/jobs/parse-constraints").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Finish by 6am tomorrow, can wait 4 hours", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PARSED"))
                .andExpect(jsonPath("$.data.fields.deadline").value("2026-11-02T22:00:00"))
                .andExpect(jsonPath("$.data.fields.flexibilityWindowHours").value(4))
                .andExpect(jsonPath("$.data.evidence[0].field").exists())
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Singapore"))
                .andExpect(jsonPath("$.data.parsedAt").value("2026-11-02T06:00:00"));
        verifyNoInteractions(jobRepository);
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("Ambiguous text is a 200 with a question, not an error")
    void ambiguousIsNotAnError() throws Exception {
        mockMvc.perform(post("/api/jobs/parse-constraints").contentType(MediaType.APPLICATION_JSON)
                        .content(body("finish by 6", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.data.ambiguities[0].field").value("deadline"))
                .andExpect(jsonPath("$.data.ambiguities[0].reason").value("AM_OR_PM"))
                .andExpect(jsonPath("$.data.ambiguities[0].message").value("Did you mean 6am or 6pm?"))
                .andExpect(jsonPath("$.data.fields.deadline").doesNotExist());
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("The form draft is checked with the text: a deadline before the form's start is INVALID")
    void draftIsValidatedWithTheText() throws Exception {
        mockMvc.perform(post("/api/jobs/parse-constraints").contentType(MediaType.APPLICATION_JSON)
                        .content(body("finish by 6am tomorrow", Map.of("originalStartTime", "2026-11-03T14:00:00",
                                "isExempt", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVALID"))
                .andExpect(jsonPath("$.data.validationError")
                        .value("deadline must be greater than or equal to originalStartTime"))
                .andExpect(jsonPath("$.data.fields.isExempt").value(false));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("Text of exactly 1,000 characters is accepted; 1,001 is a 400")
    void lengthLimit() throws Exception {
        mockMvc.perform(post("/api/jobs/parse-constraints").contentType(MediaType.APPLICATION_JSON)
                        .content(body("a".repeat(1000), null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/parse-constraints").contentType(MediaType.APPLICATION_JSON)
                        .content(body("a".repeat(1001), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io")
    @DisplayName("Blank text and unknown time zones are 400s")
    void badRequests() throws Exception {
        mockMvc.perform(post("/api/jobs/parse-constraints").contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", null)))
                .andExpect(status().isBadRequest());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rawConstraints", "finish by 6pm");
        request.put("timeZone", "Mars/Olympus");
        mockMvc.perform(post("/api/jobs/parse-constraints").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unknown timeZone")));
    }

    @Test
    @DisplayName("Without a login the endpoint is rejected (403 in this test slice, as in JobControllerTest)")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/jobs/parse-constraints").contentType(MediaType.APPLICATION_JSON)
                        .content(body("finish by 6pm", null)))
                .andExpect(status().isForbidden());
    }

    private String body(String text, Map<String, Object> draft) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rawConstraints", text);
        if (draft != null) {
            request.put("draft", draft);
        }
        return json.writeValueAsString(request);
    }
}
