package com.gacs.backend.repository;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.model.PredictedPriceKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Repository
public interface PredictedPriceRepository extends JpaRepository<PredictedPrice, PredictedPriceKey> {
    // Model kinds findLatestForecastForModel accepts - the prefixes data_scripts writes to model_version.
    Set<String> MODEL_KINDS = Set.of("lstm", "lear");

    @Query("select p from PredictedPrice p where p.location = :location " +
        "and p.intervalStartUtc >= :start and p.intervalStartUtc < :end " +
        "and p.generatedAt = (select max(p2.generatedAt) from PredictedPrice p2 " +
        "where p2.location = p.location and p2.intervalStartUtc = p.intervalStartUtc) " +
        "order by p.intervalStartUtc asc")
    List<PredictedPrice> findLatestForecast(@Param("location") String location,
        @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // Same as findLatestForecast, but scoped to one model kind (lstm/lear). model_version is always
    // "<kind>_<target>_<date>" (data_scripts/update.py), so the prefix identifies the kind. The
    // subquery must apply the same filter, otherwise a newer run from the other model hides this one.
    // '_' is a LIKE wildcard, so it is escaped ('!') to match a literal underscore - otherwise
    // 'lstm_%' would also match e.g. "lstm2_...".
    @Query("select p from PredictedPrice p where p.location = :location " +
        "and p.modelVersion like concat(:modelKind, '!_%') escape '!' " +
        "and p.intervalStartUtc >= :start and p.intervalStartUtc < :end " +
        "and p.generatedAt = (select max(p2.generatedAt) from PredictedPrice p2 " +
        "where p2.location = p.location and p2.intervalStartUtc = p.intervalStartUtc " +
        "and p2.modelVersion like concat(:modelKind, '!_%') escape '!') " +
        "order by p.intervalStartUtc asc")
    List<PredictedPrice> findLatestForecastForModel(@Param("location") String location,
        @Param("modelKind") String modelKind,
        @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

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
