package com.gacs.backend.repository;

import com.gacs.backend.model.ElectricalPrice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ElectricalPriceRepositoryTest {

    @Autowired
    private ElectricalPriceRepository repository;

    private void save(String location, LocalDateTime interval, Double price) {
        repository.save(ElectricalPrice.builder().location(location).intervalStartUtc(interval).sppUsdMwh(price).build());
    }

    @Test
    @DisplayName("averageByHourOfDay groups by hour, respects [start, end) and location, and skips null prices")
    void averagesByHourOfDay() {
        LocalDateTime day1 = LocalDateTime.of(2025, 10, 1, 0, 0);
        save("LZ_TEST", day1, 10.0);
        save("LZ_TEST", day1.plusDays(1), 30.0);
        save("LZ_TEST", day1.plusHours(5), 50.0);
        save("LZ_TEST", day1.plusHours(6), null);
        save("LZ_TEST", day1.plusMonths(1), 999.0);
        save("LZ_OTHER", day1, 777.0);

        List<Object[]> rows = repository.averageByHourOfDay("LZ_TEST", day1, day1.plusMonths(1));

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(((Number) r[0]).intValue()).isEqualTo(0);
            assertThat(((Number) r[1]).doubleValue()).isEqualTo(20.0);
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(((Number) r[0]).intValue()).isEqualTo(5);
            assertThat(((Number) r[1]).doubleValue()).isEqualTo(50.0);
        });
    }

    @Test
    @DisplayName("findLatestIntervalStartUtcForLocation is per location")
    void latestPerLocation() {
        LocalDateTime t = LocalDateTime.of(2025, 10, 1, 0, 0);
        save("LZ_TEST", t, 1.0);
        save("LZ_TEST", t.plusHours(3), 1.0);
        save("LZ_OTHER", t.plusDays(9), 1.0);

        assertThat(repository.findLatestIntervalStartUtcForLocation("LZ_TEST")).isEqualTo(t.plusHours(3));
    }
}
