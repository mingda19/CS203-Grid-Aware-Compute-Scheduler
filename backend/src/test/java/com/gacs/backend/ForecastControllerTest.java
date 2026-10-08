package com.gacs.backend;

import com.gacs.backend.config.SecurityConfig;
import com.gacs.backend.controller.ForecastController;
import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.PredictedPriceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ForecastController.class)
@Import(SecurityConfig.class)
class ForecastControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PredictedPriceRepository repo;

    private static PredictedPrice row(String hour, String generatedAt, double price) {
        return new PredictedPrice(LocalDateTime.parse(hour), "LZ_NORTH", "xgb_v1", LocalDateTime.parse(generatedAt), price);
    }

    @Test
    @WithMockUser
    void returnsForecastPoints_WithoutConfidenceScore() throws Exception {
        when(repo.findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAscGeneratedAtAsc(
            eq("LZ_NORTH"), any(), any()
        )).thenReturn(List.of(row("2026-10-09T00:00:00", "2026-10-08T12:00:00", 30.5)));

        mockMvc.perform(get("/api/forecasts/prices").param("startDate", "2026-10-09"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.location").value("LZ_NORTH"))
            .andExpect(jsonPath("$.data.points.length()").value(1))
            .andExpect(jsonPath("$.data.points[0].predictedPriceUsdMwh").value(30.5))
            .andExpect(jsonPath("$.data.points[0].modelVersion").value("xgb_v1"))
            .andExpect(jsonPath("$.data.points[0].confidenceScore").doesNotExist());
    }

    @Test
    @WithMockUser
    void keepsLatestRun_WhenSeveralRunsCoverTheSameHour() throws Exception {
        when(repo.findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAscGeneratedAtAsc(
            any(), any(), any()
        )).thenReturn(List.of(
            row("2026-10-09T00:00:00", "2026-10-07T12:00:00", 10.0),
            row("2026-10-09T00:00:00", "2026-10-08T12:00:00", 20.0),
            row("2026-10-09T01:00:00", "2026-10-08T12:00:00", 25.0)));

        mockMvc.perform(get("/api/forecasts/prices").param("startDate", "2026-10-09"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.points.length()").value(2))
            .andExpect(jsonPath("$.data.points[0].predictedPriceUsdMwh").value(20.0));
    }

    @Test
    @WithMockUser
    void returnsEmptyPoints_WhenNoForecastExists() throws Exception {
        when(repo.findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAscGeneratedAtAsc(
            any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/forecasts/prices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.points.length()").value(0));
    }

    @Test
    @WithMockUser
    void endDateIsInclusive() throws Exception {
        mockMvc.perform(get("/api/forecasts/prices").param("startDate", "2026-10-09").param("endDate", "2026-10-09"))
            .andExpect(status().isOk());
        verify(repo).findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAscGeneratedAtAsc(
            "LZ_NORTH", LocalDateTime.parse("2026-10-09T00:00:00"), LocalDateTime.parse("2026-10-10T00:00:00"));
    }

    @Test
    @WithMockUser
    void returns400_WhenEndBeforeStart() throws Exception {
        mockMvc.perform(get("/api/forecasts/prices").param("startDate", "2026-10-09").param("endDate", "2026-10-08"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithMockUser
    void returns400_WhenRangeTooLong() throws Exception {
        mockMvc.perform(get("/api/forecasts/prices").param("startDate", "2026-10-01").param("endDate", "2026-12-01"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void returns400_WhenLocationBlank() throws Exception {
        mockMvc.perform(get("/api/forecasts/prices").param("location", " "))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void returnsError_WhenRepositoryFails() throws Exception {
        when(repo.findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAscGeneratedAtAsc(
            any(), any(), any())).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/api/forecasts/prices"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/forecasts/prices"))
            .andExpect(status().isForbidden());
    }
}
