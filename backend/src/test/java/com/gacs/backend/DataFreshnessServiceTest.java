package com.gacs.backend;

import com.gacs.backend.dto.DataFreshnessResponse;
import com.gacs.backend.repository.DataCenterRepository;
import com.gacs.backend.repository.ElectricalPriceRepository;
import com.gacs.backend.repository.FuelGenerationMonthlyRepository;
import com.gacs.backend.repository.FuelMixHourlyRepository;
import com.gacs.backend.repository.FuelPriceRepository;
import com.gacs.backend.repository.HourlyDemandForecastRepository;
import com.gacs.backend.repository.LoadDataRepository;
import com.gacs.backend.repository.SolarRepository;
import com.gacs.backend.repository.WindRepository;
import com.gacs.backend.service.DataFreshnessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataFreshnessServiceTest {

    private ElectricalPriceRepository electricalPrice;
    private FuelPriceRepository fuelPrice;
    private FuelMixHourlyRepository fuelMix;
    private FuelGenerationMonthlyRepository monthlyGeneration;
    private HourlyDemandForecastRepository demand;
    private LoadDataRepository load;
    private SolarRepository solar;
    private WindRepository wind;
    private DataCenterRepository dataCenters;

    @BeforeEach
    void setUp() {
        electricalPrice = mock(ElectricalPriceRepository.class);
        fuelPrice = mock(FuelPriceRepository.class);
        fuelMix = mock(FuelMixHourlyRepository.class);
        monthlyGeneration = mock(FuelGenerationMonthlyRepository.class);
        demand = mock(HourlyDemandForecastRepository.class);
        load = mock(LoadDataRepository.class);
        solar = mock(SolarRepository.class);
        wind = mock(WindRepository.class);
        dataCenters = mock(DataCenterRepository.class);
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

    private DataFreshnessService service() {
        return new DataFreshnessService(electricalPrice, fuelPrice, fuelMix, monthlyGeneration,
                demand, load, solar, wind, dataCenters);
    }

    private void whenAllLatest(LocalDateTime timestamp) {
        when(electricalPrice.findLatestIntervalStartUtc()).thenReturn(timestamp);
        when(fuelPrice.findLatestPeriod()).thenReturn(timestamp);
        when(fuelMix.findLatestPeriod()).thenReturn(timestamp);
        when(monthlyGeneration.findLatestPeriod()).thenReturn(timestamp);
        when(demand.findLatestPeriod()).thenReturn(timestamp);
        when(load.findLatestTime()).thenReturn(timestamp);
        when(solar.findLatestTime()).thenReturn(timestamp);
        when(wind.findLatestTime()).thenReturn(timestamp);
        when(dataCenters.findLatestTime()).thenReturn(timestamp);
    }
}
