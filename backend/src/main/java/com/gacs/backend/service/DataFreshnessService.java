package com.gacs.backend.service;

import com.gacs.backend.dto.DataFreshnessResponse;
import com.gacs.backend.repository.DataCenterRepository;
import com.gacs.backend.repository.ElectricalPriceRepository;
import com.gacs.backend.repository.FuelGenerationMonthlyRepository;
import com.gacs.backend.repository.FuelMixHourlyRepository;
import com.gacs.backend.repository.FuelPriceRepository;
import com.gacs.backend.repository.LoadForecastDamRepository;
import com.gacs.backend.repository.LoadDataRepository;
import com.gacs.backend.repository.SolarRepository;
import com.gacs.backend.repository.WindRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Service
public class DataFreshnessService {

    private final List<DatasetDefinition> definitions;
    private final Map<String, Boolean> staleState = new LinkedHashMap<>();
    private final Map<Long, DataFreshnessResponse.FreshnessNotification> notifications = new LinkedHashMap<>();
    private final AtomicLong notificationIds = new AtomicLong();

    public DataFreshnessService(
            ElectricalPriceRepository electricalPriceRepository,
            FuelPriceRepository fuelPriceRepository,
            FuelMixHourlyRepository fuelMixHourlyRepository,
            FuelGenerationMonthlyRepository fuelGenerationMonthlyRepository,
            LoadForecastDamRepository loadForecastDamRepository,
            LoadDataRepository loadDataRepository,
            SolarRepository solarRepository,
            WindRepository windRepository,
            DataCenterRepository dataCenterRepository) {
        definitions = List.of(
                new DatasetDefinition("electrical_price", "Daily ERCOT price", Duration.ofHours(26), electricalPriceRepository::findLatestIntervalStartUtc),
                new DatasetDefinition("fuel_price", "Daily fuel price", Duration.ofDays(3), fuelPriceRepository::findLatestPeriod),
                new DatasetDefinition("fuel_mix_hourly", "Hourly fuel mix", Duration.ofHours(3), fuelMixHourlyRepository::findLatestPeriod),
                new DatasetDefinition("fuel_generation_monthly", "Monthly generation mix", Duration.ofDays(35), fuelGenerationMonthlyRepository::findLatestPeriod),
                new DatasetDefinition("load_forecast_dam", "ERCOT day-ahead load forecast", Duration.ofHours(26), loadForecastDamRepository::findLatestIntervalStartUtc),
                new DatasetDefinition("load_data", "Hourly weather load data", Duration.ofHours(3), loadDataRepository::findLatestTime),
                new DatasetDefinition("solar", "Hourly solar weather data", Duration.ofHours(3), solarRepository::findLatestTime),
                new DatasetDefinition("wind", "Hourly wind weather data", Duration.ofHours(3), windRepository::findLatestTime),
                new DatasetDefinition("data_centers", "Hourly data center weather data", Duration.ofHours(3), dataCenterRepository::findLatestTime)
        );
    }

    @Scheduled(fixedRateString = "${data-freshness.check-interval-ms:300000}")
    public void scheduledCheck() {
        checkFreshness();
    }

    public synchronized DataFreshnessResponse checkFreshness() {
        Instant checkedAt = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(checkedAt, ZoneOffset.UTC);
        List<DataFreshnessResponse.DatasetFreshness> statuses = new ArrayList<>();

        for (DatasetDefinition definition : definitions) {
            try {
                LocalDateTime latest = definition.latestTimestamp().get();
                statuses.add(recordStatus(definition, latest, now));
            } catch (RuntimeException exception) {
                statuses.add(new DataFreshnessResponse.DatasetFreshness(
                        definition.name(), null, "DATABASE_UNAVAILABLE", null,
                        "Database connectivity check failed: " + safeMessage(exception)));
            }
        }

        return new DataFreshnessResponse(statuses, unreadNotifications(), checkedAt);
    }

    public synchronized List<DataFreshnessResponse.FreshnessNotification> unreadNotifications() {
        return notifications.values().stream().filter(notification -> !notification.read()).toList();
    }

    public synchronized boolean markRead(long notificationId) {
        DataFreshnessResponse.FreshnessNotification notification = notifications.get(notificationId);
        if (notification == null) {
            return false;
        }
        notifications.put(notificationId, new DataFreshnessResponse.FreshnessNotification(
                notification.id(), notification.dataset(), notification.message(), notification.createdAt(), true));
        return true;
    }

    private DataFreshnessResponse.DatasetFreshness recordStatus(
            DatasetDefinition definition, LocalDateTime latest, LocalDateTime now) {
        if (latest == null) {
            updateTransition(definition, true, "No records have been stored for this dataset.");
            return new DataFreshnessResponse.DatasetFreshness(
                    definition.name(), null, "MISSING", null, "No records have been stored for this dataset.");
        }

        long ageMinutes = Math.max(0, Duration.between(latest, now).toMinutes());
        boolean stale = Duration.between(latest, now).compareTo(definition.threshold()) > 0;
        String status = stale ? "STALE" : "HEALTHY";
        String message = stale
                ? definition.label() + " has not received a new record for " + ageMinutes + " minutes."
                : definition.label() + " is receiving data.";
        updateTransition(definition, stale, message);
        return new DataFreshnessResponse.DatasetFreshness(
                definition.name(), latest.toInstant(ZoneOffset.UTC), status, ageMinutes, message);
    }

    private void updateTransition(DatasetDefinition definition, boolean stale, String message) {
        boolean wasStale = staleState.getOrDefault(definition.name(), false);
        staleState.put(definition.name(), stale);
        if (stale && !wasStale) {
            long id = notificationIds.incrementAndGet();
            notifications.put(id, new DataFreshnessResponse.FreshnessNotification(
                    id, definition.name(), message, Instant.now(), false));
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record DatasetDefinition(String name, String label, Duration threshold, Supplier<LocalDateTime> latestTimestamp) {}
}
