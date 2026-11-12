package com.gacs.backend;

import com.gacs.backend.config.SecurityConfig;
import com.gacs.backend.controller.PriceHistoryController;
import com.gacs.backend.model.ElectricalPrice;
import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.model.RmseReference;
import com.gacs.backend.repository.ElectricalPriceRepository;
import com.gacs.backend.repository.PredictedPriceRepository;
import com.gacs.backend.repository.RmseReferenceRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @MockitoBean
    private RmseReferenceRepository rmse;

    private static PredictedPrice prediction(String interval, String version, String generated, double price) {
        return PredictedPrice.builder()
            .intervalStartUtc(LocalDateTime.parse(interval))
            .location("LZ_NORTH")
            .modelVersion(version)
            .generatedAt(LocalDateTime.parse(generated))
            .predictedPrice(price)
            .build();
    }

    @Test
    @WithMockUser
    void addsTypicalError_ForThePointsTargetAndHour() throws Exception {
        var y0At5 = prediction("2026-10-09T05:00:00", "lear_y0_2026-10-07", "2026-10-07T00:10:00", 30.0);
        var y1At6 = prediction("2026-10-09T06:00:00", "lear_y1_2026-10-06", "2026-10-06T00:10:00", 31.0);
        var noRef = prediction("2026-10-09T07:00:00", "lear_y0_2026-10-07", "2026-10-07T00:10:00", 32.0);
        when(predictions.findLatestForecastForModel(eq("LZ_NORTH"), eq("lear"), any(), any()))
            .thenReturn(List.of(y0At5, y1At6, noRef));
        when(rmse.findByModelKind("lear")).thenReturn(List.of(
            RmseReference.builder().modelKind("lear").target("y0").hourUtc(5).rmse(4.5).build(),
            RmseReference.builder().modelKind("lear").target("y0").hourUtc(6).rmse(99.0).build(),
            RmseReference.builder().modelKind("lear").target("y1").hourUtc(6).rmse(7.25).build()));

        mockMvc.perform(get("/api/prices/forecast").param("model", "lear"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.points[0].typicalErrorUsdMwh").value(4.5))
            .andExpect(jsonPath("$.data.points[1].typicalErrorUsdMwh").value(7.25))
            .andExpect(jsonPath("$.data.points[2].typicalErrorUsdMwh").doesNotExist());
    }

    @Test
    @WithMockUser
    void accuracy_UsesLatestRunBeforeTheHour_AndSkipsHoursWithoutActuals() throws Exception {
        var hour = "2026-10-09T05:00:00";
        // The repository query already drops other models, runs made after the hour and null prices.
        when(predictions.findPastForecastsForModel(eq("LZ_NORTH"), eq("lear"), any(), any()))
            .thenReturn(List.of(
                prediction(hour, "lear_y1_2026-10-06", "2026-10-06T00:10:00", 20.0),
                prediction(hour, "lear_y0_2026-10-07", "2026-10-07T00:10:00", 25.0), // latest run: used
                prediction("2026-10-09T06:00:00", "lear_y0_2026-10-07", "2026-10-07T00:10:00", 26.0))); // no actual
        when(prices.findByLocationAndIntervalStartUtcBetweenOrderByIntervalStartUtcAsc(eq("LZ_NORTH"), any(), any()))
            .thenReturn(List.of(ElectricalPrice.builder()
                .intervalStartUtc(LocalDateTime.parse(hour)).location("LZ_NORTH").sppUsdMwh(28.0).build()));

        mockMvc.perform(get("/api/prices/forecast-accuracy"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.modelKind").value("lear"))
            .andExpect(jsonPath("$.data.days").value(7))
            .andExpect(jsonPath("$.data.points.length()").value(1))
            .andExpect(jsonPath("$.data.points[0].predictedPrice").value(25.0))
            .andExpect(jsonPath("$.data.points[0].actualPrice").value(28.0))
            .andExpect(jsonPath("$.data.points[0].modelVersion").value("lear_y0_2026-10-07"));
    }

    @Test
    @WithMockUser
    void accuracy_Returns400_WhenDaysOutOfRange() throws Exception {
        mockMvc.perform(get("/api/prices/forecast-accuracy").param("days", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/prices/forecast-accuracy").param("days", "31")).andExpect(status().isBadRequest());
    }

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
        when(predictions.findLatestForecastForModel(eq("LZ_NORTH"), eq("lstm"), any(), any())).thenReturn(List.of(row));

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
    void returnsStoredConfidenceScoreDirectly() throws Exception {
        var interval = LocalDateTime.parse("2026-10-20T12:00:00");
        var generated = LocalDateTime.parse("2026-10-19T12:00:00");
        var row = PredictedPrice.builder()
            .intervalStartUtc(interval)
            .location("LZ_NORTH")
            .modelVersion("xgb_v1")
            .generatedAt(generated)
            .predictedPrice(30.5)
            // Already the final 0-100 score (data_scripts/predictions.py's
            // confidence_scores()) - the API must forward it unchanged, not
            // reapply any ratio-to-score conversion on top of it.
            .confidenceScore(72.5)
            .build();
        when(predictions.findLatestForecastForModel(eq("LZ_NORTH"), eq("lstm"), any(), any())).thenReturn(List.of(row));

        mockMvc.perform(get("/api/prices/forecast"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.points[0].confidenceScore").value(72.5));

    }

    @Test
    @WithMockUser
    void returnsEmptyPoints_WhenNoForecastExists() throws Exception {
        when(predictions.findLatestForecastForModel(any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/prices/forecast"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.points.length()").value(0));
    }

    @Test
    @WithMockUser
    void defaultsToLstmModel() throws Exception {
        when(predictions.findLatestForecastForModel(any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/prices/forecast"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.modelKind").value("lstm"));
        verify(predictions).findLatestForecastForModel(eq("LZ_NORTH"), eq("lstm"), any(), any());
    }

    @Test
    @WithMockUser
    void servesLearModel_WhenRequested() throws Exception {
        var row = PredictedPrice.builder()
            .intervalStartUtc(LocalDateTime.parse("2026-10-09T00:00:00"))
            .location("LZ_NORTH")
            .modelVersion("lear_y0_2026-10-08")
            .generatedAt(LocalDateTime.parse("2026-10-08T12:00:00"))
            .predictedPrice(41.0)
            .build();
        when(predictions.findLatestForecastForModel(eq("LZ_NORTH"), eq("lear"), any(), any())).thenReturn(List.of(row));

        mockMvc.perform(get("/api/prices/forecast").param("model", "LEAR"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.modelKind").value("lear"))
            .andExpect(jsonPath("$.data.points[0].modelVersion").value("lear_y0_2026-10-08"))
            .andExpect(jsonPath("$.data.points[0].predictedPrice").value(41.0));
    }

    @Test
    @WithMockUser
    void returns400_WhenModelUnknown() throws Exception {
        mockMvc.perform(get("/api/prices/forecast").param("model", "xgboost")).andExpect(status().isBadRequest());
        verify(predictions, never()).findLatestForecastForModel(any(), any(), any(), any());
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
        when(predictions.findLatestForecastForModel(any(), any(), any(), any())).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/api/prices/forecast"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/prices/forecast")).andExpect(status().isForbidden());
    }
}
