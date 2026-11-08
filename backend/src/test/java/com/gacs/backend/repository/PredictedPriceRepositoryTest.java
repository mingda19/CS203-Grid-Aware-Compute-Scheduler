package com.gacs.backend.repository;

import com.gacs.backend.model.PredictedPrice;
import org.junit.jupiter.api.BeforeEach;
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
class PredictedPriceRepositoryTest {

    private static final LocalDateTime HOUR_1 = LocalDateTime.parse("2026-11-10T00:00:00");
    private static final LocalDateTime HOUR_2 = LocalDateTime.parse("2026-11-10T01:00:00");

    @Autowired
    private PredictedPriceRepository repository;

    @BeforeEach
    void seed() {
        repository.saveAll(List.of(
            row(HOUR_1, "lstm_y0_2026-11-02", "2026-11-08T06:00:00", 30.0),
            row(HOUR_1, "lstm_y0_2026-11-02", "2026-11-09T06:00:00", 31.0), // newer LSTM run
            row(HOUR_2, "lstm_y0_2026-11-02", "2026-11-09T06:00:00", 32.0),
            // LEAR ran after LSTM for HOUR_1 - must not hide the LSTM forecast, or vice versa
            row(HOUR_1, "lear_y0_2026-11-02", "2026-11-09T07:00:00", 40.0),
            row(HOUR_2, "lear_y0_2026-11-02", "2026-11-09T07:00:00", 42.0),
            // Newest of all, but a different kind that merely starts with "lstm" - '_' must match literally
            row(HOUR_1, "lstm2_y0_2026-11-02", "2026-11-09T08:00:00", 77.0),
            row(HOUR_1, "lstm_y0_2026-11-02", "2026-11-09T06:00:00", 99.0, "LZ_HOUSTON")
        ));
    }

    @Test
    @DisplayName("LSTM forecast returns the newest LSTM run per hour even when LEAR ran later")
    void returnsLatestLstmRun() {
        var rows = repository.findLatestForecastForModel("LZ_NORTH", "lstm", HOUR_1, HOUR_2.plusHours(1));

        assertThat(rows).extracting(PredictedPrice::getPredictedPrice).containsExactly(31.0, 32.0);
        assertThat(rows).allMatch(r -> r.getModelVersion().startsWith("lstm_"));
    }

    @Test
    @DisplayName("LEAR forecast returns only LEAR rows")
    void returnsLearRows() {
        var rows = repository.findLatestForecastForModel("LZ_NORTH", "lear", HOUR_1, HOUR_2.plusHours(1));

        assertThat(rows).extracting(PredictedPrice::getPredictedPrice).containsExactly(40.0, 42.0);
        assertThat(rows).allMatch(r -> r.getModelVersion().startsWith("lear_"));
    }

    @Test
    @DisplayName("End bound is exclusive and other locations are excluded")
    void respectsWindowAndLocation() {
        var rows = repository.findLatestForecastForModel("LZ_NORTH", "lstm", HOUR_1, HOUR_2);

        assertThat(rows).extracting(PredictedPrice::getPredictedPrice).containsExactly(31.0);
    }

    private static PredictedPrice row(LocalDateTime hour, String version, String generatedAt, double price) {
        return row(hour, version, generatedAt, price, "LZ_NORTH");
    }

    private static PredictedPrice row(LocalDateTime hour, String version, String generatedAt, double price,
                                      String location) {
        return PredictedPrice.builder()
            .intervalStartUtc(hour)
            .location(location)
            .modelVersion(version)
            .generatedAt(LocalDateTime.parse(generatedAt))
            .predictedPrice(price)
            .build();
    }
}
