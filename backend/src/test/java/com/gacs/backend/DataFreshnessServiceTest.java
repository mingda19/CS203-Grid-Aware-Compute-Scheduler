package com.gacs.backend;

import com.gacs.backend.dto.DataFreshnessResponse;
import com.gacs.backend.repository.DataCenterRepository;
import com.gacs.backend.repository.ElectricalPriceRepository;
import com.gacs.backend.repository.FuelGenerationMonthlyRepository;
import com.gacs.backend.repository.FuelMixHourlyRepository;
import com.gacs.backend.repository.FuelPriceRepository;
import com.gacs.backend.repository.LoadDataRepository;
import com.gacs.backend.repository.SolarRepository;
import com.gacs.backend.repository.WindRepository;
import com.gacs.backend.service.DataFreshnessService;
import com.gacs.backend.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class DataFreshnessServiceTest {

    private ElectricalPriceRepository electricalPrice;
    private FuelPriceRepository fuelPrice;
    private FuelMixHourlyRepository fuelMix;
    private FuelGenerationMonthlyRepository monthlyGeneration;
    private LoadDataRepository load;
    private SolarRepository solar;
    private WindRepository wind;
    private DataCenterRepository dataCenters;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        electricalPrice = mock(ElectricalPriceRepository.class);
        fuelPrice = mock(FuelPriceRepository.class);
        fuelMix = mock(FuelMixHourlyRepository.class);
        monthlyGeneration = mock(FuelGenerationMonthlyRepository.class);
        load = mock(LoadDataRepository.class);
        solar = mock(SolarRepository.class);
        wind = mock(WindRepository.class);
        dataCenters = mock(DataCenterRepository.class);
        emailService = mock(EmailService.class);
    }

    @Test
    void freshHourlyDataIsHealthy() {
        whenAllLatest(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20));

        DataFreshnessResponse response = service().checkFreshness();

        assertTrue(response.datasets().stream().allMatch(dataset -> dataset.status().equals("HEALTHY")));
        assertTrue(response.notifications().isEmpty());
    }

    @Test
    void missingDataIsReportedAndNotConfusedWithDatabaseFailure() {
        whenAllLatest(null);

        DataFreshnessResponse response = service().checkFreshness();

        assertTrue(response.datasets().stream().allMatch(dataset -> dataset.status().equals("MISSING")));
        assertTrue(response.datasets().stream().noneMatch(dataset -> dataset.status().equals("DATABASE_UNAVAILABLE")));
    }

    @Test
    void staleTransitionCreatesOnlyOneNotificationUntilRecovery() {
        whenAllLatest(LocalDateTime.now(ZoneOffset.UTC).minusHours(4));
        DataFreshnessService service = service();

        DataFreshnessResponse first = service.checkFreshness();
        DataFreshnessResponse second = service.checkFreshness();

        assertTrue(first.datasets().stream().anyMatch(dataset -> dataset.status().equals("STALE")));
        assertEquals(first.notifications().size(), second.notifications().size());

        whenAllLatest(LocalDateTime.now(ZoneOffset.UTC));
        DataFreshnessResponse recovered = service.checkFreshness();
        assertTrue(recovered.datasets().stream().allMatch(dataset -> dataset.status().equals("HEALTHY")));
    }

    @Test
    void lowConfidenceForecastCreatesOneNotificationPerWindow() {
        DataFreshnessService service = service();
        LocalDateTime interval = LocalDateTime.parse("2026-10-20T12:00:00");
        LocalDateTime generated = LocalDateTime.parse("2026-10-19T12:00:00");

        service.recordLowConfidenceForecast("LZ_NORTH", interval, generated, 0.69, "operator@example.com");
        service.recordLowConfidenceForecast("LZ_NORTH", interval, generated, 0.69, "operator@example.com");
        service.recordLowConfidenceForecast("LZ_NORTH", interval.plusHours(1), generated, 0.6, "operator@example.com");

        var notifications = service.unreadNotifications();
        assertEquals(2, notifications.size());
        assertEquals("Forecast · LZ_NORTH", notifications.get(0).dataset());
        assertTrue(notifications.get(0).message().contains("69%"));
        verify(emailService, times(1)).sendLowConfidenceForecastEmail(
                "operator@example.com", "LZ_NORTH", interval, 0.69, 0.7);
    }

    @Test
    void confidenceAtOrAboveThresholdDoesNotCreateNotification() {
        DataFreshnessService service = service();

        service.recordLowConfidenceForecast("LZ_NORTH", LocalDateTime.now(), LocalDateTime.now(), 0.7, "operator@example.com");
        service.recordLowConfidenceForecast("LZ_NORTH", LocalDateTime.now(), LocalDateTime.now(), null, "operator@example.com");

        assertTrue(service.unreadNotifications().isEmpty());
    }

    @Test
    void lowConfidenceNotificationCanBeMarkedRead() {
        DataFreshnessService service = service();
        service.recordLowConfidenceForecast("LZ_NORTH", LocalDateTime.now(), LocalDateTime.now(), 0.5, "operator@example.com");
        long id = service.unreadNotifications().get(0).id();

        assertTrue(service.markRead(id));
        assertTrue(service.unreadNotifications().isEmpty());
    }

    private DataFreshnessService service() {
        DataFreshnessService service = new DataFreshnessService(electricalPrice, fuelPrice, fuelMix, monthlyGeneration,
                load, solar, wind, dataCenters);
        ReflectionTestUtils.setField(service, "confidenceThreshold", 0.7);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        return service;
    }

    private void whenAllLatest(LocalDateTime timestamp) {
        when(electricalPrice.findLatestIntervalStartUtc()).thenReturn(timestamp);
        when(fuelPrice.findLatestPeriod()).thenReturn(timestamp);
        when(fuelMix.findLatestPeriod()).thenReturn(timestamp);
        when(monthlyGeneration.findLatestPeriod()).thenReturn(timestamp);
        when(load.findLatestTime()).thenReturn(timestamp);
        when(solar.findLatestTime()).thenReturn(timestamp);
        when(wind.findLatestTime()).thenReturn(timestamp);
        when(dataCenters.findLatestTime()).thenReturn(timestamp);
    }
}
