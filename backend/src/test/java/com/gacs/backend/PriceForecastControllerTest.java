package com.gacs.backend;

import com.gacs.backend.config.SecurityConfig;
import com.gacs.backend.controller.PriceHistoryController;
import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.ElectricalPriceRepository;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PriceHistoryController.class)
@Import(SecurityConfig.class)
class PriceForecastControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PredictedPriceRepository predictions;

    @MockitoBean
    private ElectricalPriceRepository prices;

    @Test
    @WithMockUser
    void returnsForecastPoints_WithoutConfidenceScore() throws Exception {
        var row = PredictedPrice.builder()
            .intervalStartUtc(LocalDateTime.parse("2026-10-09T00:00:00"))
            .location("LZ_NORTH")
            .modelVersion("xgb_v1")
            .generatedAt(LocalDateTime.parse("2026-10-08T12:00:00"))
            .predictedPrice(30.5)
            .build();
        when(predictions.findLatestForecast(eq("LZ_NORTH"), any(), any())).thenReturn(List.of(row));

        mockMvc.perform(get("/api/prices/forecast"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.horizonHours").value(48))
            .andExpect(jsonPath("$.data.points.length()").value(1))
            .andExpect(jsonPath("$.data.points[0].predictedPrice").value(30.5))
            .andExpect(jsonPath("$.data.points[0].confidenceScore").doesNotExist());
    }

    @Test
    @WithMockUser
    void convertsStoredUncertaintyRatioToConfidence() throws Exception {
        var interval = LocalDateTime.parse("2026-10-20T12:00:00");
        var generated = LocalDateTime.parse("2026-10-19T12:00:00");
        var row = PredictedPrice.builder()
            .intervalStartUtc(interval)
            .location("LZ_NORTH")
            .modelVersion("xgb_v1")
            .generatedAt(generated)
            .predictedPrice(30.5)
            .confidenceScore(1.0) // normalized error ratio; API confidence should be 0.5
            .build();
        when(predictions.findLatestForecast(eq("LZ_NORTH"), any(), any())).thenReturn(List.of(row));

        mockMvc.perform(get("/api/prices/forecast"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.points[0].confidenceScore").value(0.5));

    }

    @Test
    @WithMockUser
    void returnsEmptyPoints_WhenNoForecastExists() throws Exception {
        when(predictions.findLatestForecast(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/prices/forecast"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.points.length()").value(0));
    }

    @Test
    @WithMockUser
    void returns400_WhenHoursOutOfRange() throws Exception {
        mockMvc.perform(get("/api/prices/forecast").param("hours", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/prices/forecast").param("hours", "169")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void returns400_WhenLocationBlank() throws Exception {
        mockMvc.perform(get("/api/prices/forecast").param("location", " ")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void returnsError_WhenRepositoryFails() throws Exception {
        when(predictions.findLatestForecast(any(), any(), any())).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/api/prices/forecast"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/prices/forecast")).andExpect(status().isForbidden());
    }
}
