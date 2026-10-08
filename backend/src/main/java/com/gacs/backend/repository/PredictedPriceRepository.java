package com.gacs.backend.repository;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.model.PredictedPriceKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PredictedPriceRepository extends JpaRepository<PredictedPrice, PredictedPriceKey> {
    List<PredictedPrice> findByLocationOrderByIntervalStartUtcAsc(String location);

    List<PredictedPrice> findByLocationAndIntervalStartUtcBetweenOrderByIntervalStartUtcAsc(
        String location, LocalDateTime start, LocalDateTime end
    );

    // All predictions made for one target hour, across model versions/runs - the whole point
    // of keying on generatedAt: lets accuracy be tracked per run, not just per target hour.
    List<PredictedPrice> findByLocationAndIntervalStartUtcOrderByGeneratedAtAsc(
        String location, LocalDateTime intervalStartUtc
    );

    List<PredictedPrice> findByModelVersionOrderByIntervalStartUtcAsc(String modelVersion);
}
