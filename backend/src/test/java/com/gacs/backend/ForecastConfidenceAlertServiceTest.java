package com.gacs.backend;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.PredictedPriceRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.service.DataFreshnessService;
import com.gacs.backend.service.ForecastConfidenceAlertService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastConfidenceAlertServiceTest {

    @Test
    void checksRecentlyGeneratedForecastsAndEmailsVerifiedUsersAutomatically() {
        PredictedPriceRepository predictions = mock(PredictedPriceRepository.class);
        UserRepository users = mock(UserRepository.class);
        DataFreshnessService notifications = mock(DataFreshnessService.class);
        ForecastConfidenceAlertService alertService = new ForecastConfidenceAlertService(predictions, users, notifications);
        ReflectionTestUtils.setField(alertService, "location", "LZ_NORTH");
        ReflectionTestUtils.setField(alertService, "lookbackMinutes", 15L);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime generatedAt = now.minusMinutes(1);
        PredictedPrice recent = forecast(now.plusHours(1), generatedAt, 1.0);
        PredictedPrice old = forecast(now.plusHours(2), now.minusHours(1), 1.0);
        when(predictions.findLatestForecast(eq("LZ_NORTH"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(recent, old));
        User verified = new User("operator@example.com", "encoded", "Operator", "ROLE_USER");
        verified.setVerified(true);
        User unverified = new User("pending@example.com", "encoded", "Pending", "ROLE_USER");
        when(users.findAll()).thenReturn(List.of(verified, unverified));

        alertService.checkRecentlyGeneratedForecasts();

        verify(notifications).recordLowConfidenceForecast("LZ_NORTH", recent.getIntervalStartUtc(), generatedAt, 0.5, null);
        verify(notifications).recordLowConfidenceForecast(
                "LZ_NORTH", recent.getIntervalStartUtc(), generatedAt, 0.5, "operator@example.com");
    }

    private PredictedPrice forecast(LocalDateTime interval, LocalDateTime generatedAt, double uncertaintyRatio) {
        return PredictedPrice.builder()
                .intervalStartUtc(interval)
                .location("LZ_NORTH")
                .modelVersion("xgb_v1")
                .generatedAt(generatedAt)
                .predictedPrice(30.0)
                .confidenceScore(uncertaintyRatio)
                .build();
    }
}
