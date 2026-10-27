package com.gacs.backend;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.DataCenterRepository;
import com.gacs.backend.repository.ElectricalPriceRepository;
import com.gacs.backend.repository.FuelGenerationMonthlyRepository;
import com.gacs.backend.repository.FuelMixHourlyRepository;
import com.gacs.backend.repository.FuelPriceRepository;
import com.gacs.backend.repository.LoadDataRepository;
import com.gacs.backend.repository.PredictedPriceRepository;
import com.gacs.backend.repository.SolarRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.repository.WindRepository;
import com.gacs.backend.service.DataFreshnessService;
import com.gacs.backend.service.EmailService;
import com.gacs.backend.service.ForecastConfidenceAlertService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        // 35.0 is already the final confidence percentage (data_scripts/predictions.py's
        // confidence_scores()) - this service must forward it unchanged, not reapply any
        // ratio-to-score conversion on top of an already-converted value.
        PredictedPrice recent = forecast(now.plusHours(1), generatedAt, 35.0);
        PredictedPrice old = forecast(now.plusHours(2), now.minusHours(1), 35.0);
        when(predictions.findLatestForecast(eq("LZ_NORTH"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(recent, old));
        User verified = new User("operator@example.com", "encoded", "Operator", "ROLE_USER");
        verified.setVerified(true);
        User unverified = new User("pending@example.com", "encoded", "Pending", "ROLE_USER");
        when(users.findAll()).thenReturn(List.of(verified, unverified));

        alertService.checkRecentlyGeneratedForecasts();

        verify(notifications).recordLowConfidenceForecast("LZ_NORTH", recent.getIntervalStartUtc(), generatedAt, 35.0, null);
        verify(notifications).recordLowConfidenceForecast(
                "LZ_NORTH", recent.getIntervalStartUtc(), generatedAt, 35.0, "operator@example.com");
    }

    @Test
    void endToEndOnlyScoresBelowFortyPercentReachAnEmail() {
        // Wires a REAL DataFreshnessService (not a mock) behind the alert service, so this
        // proves the two actually compose correctly end to end - a DB-sourced score below the
        // 40% baseline reaches an email, one at or above it does not - not just that each
        // service is correct in isolation.
        PredictedPriceRepository predictions = mock(PredictedPriceRepository.class);
        UserRepository users = mock(UserRepository.class);
        EmailService emailService = mock(EmailService.class);
        DataFreshnessService realNotifications = new DataFreshnessService(
                mock(ElectricalPriceRepository.class), mock(FuelPriceRepository.class),
                mock(FuelMixHourlyRepository.class), mock(FuelGenerationMonthlyRepository.class),
                mock(LoadDataRepository.class), mock(SolarRepository.class),
                mock(WindRepository.class), mock(DataCenterRepository.class));
        ReflectionTestUtils.setField(realNotifications, "emailService", emailService);
        ReflectionTestUtils.setField(realNotifications, "confidenceThreshold", 40.0);

        ForecastConfidenceAlertService alertService =
                new ForecastConfidenceAlertService(predictions, users, realNotifications);
        ReflectionTestUtils.setField(alertService, "location", "LZ_NORTH");
        ReflectionTestUtils.setField(alertService, "lookbackMinutes", 15L);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime generatedAt = now.minusMinutes(1);
        PredictedPrice belowBaseline = forecast(now.plusHours(1), generatedAt, 25.0);
        PredictedPrice atBaseline = forecast(now.plusHours(2), generatedAt, 40.0);
        when(predictions.findLatestForecast(eq("LZ_NORTH"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(belowBaseline, atBaseline));
        User verified = new User("operator@example.com", "encoded", "Operator", "ROLE_USER");
        verified.setVerified(true);
        when(users.findAll()).thenReturn(List.of(verified));

        alertService.checkRecentlyGeneratedForecasts();

        verify(emailService, times(1)).sendLowConfidenceForecastEmail(
                eq("operator@example.com"), eq("LZ_NORTH"), eq(belowBaseline.getIntervalStartUtc()), eq(25.0), eq(40.0));
        verify(emailService, never()).sendLowConfidenceForecastEmail(
                any(), any(), eq(atBaseline.getIntervalStartUtc()), any(Double.class), any(Double.class));
    }

    private PredictedPrice forecast(LocalDateTime interval, LocalDateTime generatedAt, double confidenceScore) {
        return PredictedPrice.builder()
                .intervalStartUtc(interval)
                .location("LZ_NORTH")
                .modelVersion("xgb_v1")
                .generatedAt(generatedAt)
                .predictedPrice(30.0)
                .confidenceScore(confidenceScore)
                .build();
    }
}
