package com.gacs.backend.service;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.PredictedPriceRepository;
import com.gacs.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class ForecastConfidenceAlertService {

    private static final int FORECAST_HORIZON_HOURS = 168;

    private final PredictedPriceRepository predictions;
    private final UserRepository users;
    private final DataFreshnessService notifications;

    @Value("${forecast.confidence-alert.location:LZ_NORTH}")
    private String location;

    @Value("${forecast.confidence-alert.lookback-minutes:15}")
    private long lookbackMinutes;

    public ForecastConfidenceAlertService(
            PredictedPriceRepository predictions,
            UserRepository users,
            DataFreshnessService notifications) {
        this.predictions = predictions;
        this.users = users;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${forecast.confidence-alert.check-interval-ms:60000}")
    public void checkRecentlyGeneratedForecasts() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime generatedAfter = now.minusMinutes(lookbackMinutes);
        List<PredictedPrice> recentForecasts = predictions.findLatestForecast(
                location, now, now.plusHours(FORECAST_HORIZON_HOURS)).stream()
                .filter(prediction -> prediction.getGeneratedAt() != null
                        && !prediction.getGeneratedAt().isBefore(generatedAfter))
                .toList();

        var verifiedUsers = users.findAll().stream().filter(user -> user.isVerified()).toList();
        for (PredictedPrice prediction : recentForecasts) {
            Double confidence = ForecastConfidenceScore.fromUncertaintyRatio(prediction.getConfidenceScore());
            if (confidence == null) {
                continue;
            }
            notifications.recordLowConfidenceForecast(
                    location, prediction.getIntervalStartUtc(), prediction.getGeneratedAt(), confidence, null);
            for (var user : verifiedUsers) {
                notifications.recordLowConfidenceForecast(
                        location, prediction.getIntervalStartUtc(), prediction.getGeneratedAt(), confidence, user.getEmail());
            }
        }
    }

}
