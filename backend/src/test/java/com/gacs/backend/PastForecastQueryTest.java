package com.gacs.backend;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.PredictedPriceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code findPastForecastsForModel}: the filters the forecast-accuracy endpoint relies on. */
@SpringBootTest
@ActiveProfiles("test")
class PastForecastQueryTest {

    private static final String LOCATION = "LZ_PAST_QUERY_TEST";
    private static final LocalDateTime HOUR = LocalDateTime.parse("2026-10-09T05:00:00");

    @Autowired
    private PredictedPriceRepository predictions;

    @AfterEach
    void cleanUp() {
        predictions.deleteAll(predictions.findByLocationOrderByIntervalStartUtcAsc(LOCATION));
    }

    private PredictedPrice row(String version, String generated, Double price) {
        return PredictedPrice.builder().intervalStartUtc(HOUR).location(LOCATION).modelVersion(version)
            .generatedAt(LocalDateTime.parse(generated)).predictedPrice(price).build();
    }

    @Test
    void keepsOnlyThisModelsRunsMadeBeforeTheHourWithAPrice() {
        predictions.saveAll(List.of(
            row("lear_y0_2026-10-07", "2026-10-07T00:10:00", 25.0), // kept
            row("lear_y1_2026-10-06", "2026-10-06T00:10:00", 20.0), // kept
            row("lear_y0_2026-10-09", "2026-10-09T06:00:00", 99.0), // made after the hour
            row("lear_y0_2026-10-08", "2026-10-08T00:10:00", null), // no price
            row("lstm_y0_2026-10-08", "2026-10-08T00:10:00", 50.0))); // other model

        var found = predictions.findPastForecastsForModel(LOCATION, "lear", HOUR.minusDays(1), HOUR.plusDays(1));

        assertThat(found).extracting(PredictedPrice::getModelVersion)
            .containsExactlyInAnyOrder("lear_y0_2026-10-07", "lear_y1_2026-10-06");
    }
}
