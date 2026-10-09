package com.gacs.backend.repository;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.model.PredictedPriceKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PredictedPriceRepository extends JpaRepository<PredictedPrice, PredictedPriceKey> {
    @Query("select p from PredictedPrice p where p.location = :location " +
        "and p.intervalStartUtc >= :start and p.intervalStartUtc < :end " +
        "and p.generatedAt = (select max(p2.generatedAt) from PredictedPrice p2 " +
        "where p2.location = p.location and p2.intervalStartUtc = p.intervalStartUtc) " +
        "order by p.intervalStartUtc asc")
    List<PredictedPrice> findLatestForecast(@Param("location") String location,
        @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    List<PredictedPrice> findByLocationOrderByIntervalStartUtcAsc(String location);

    List<PredictedPrice> findByLocationAndIntervalStartUtcBetweenOrderByIntervalStartUtcAsc(
        String location, LocalDateTime start, LocalDateTime end
    );

    List<PredictedPrice> findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAscGeneratedAtAsc(
        String location, LocalDateTime startInclusive, LocalDateTime endExclusive
    );

    // All predictions made for one target hour, across model versions/runs - the whole point
    // of keying on generatedAt: lets accuracy be tracked per run, not just per target hour.
    List<PredictedPrice> findByLocationAndIntervalStartUtcOrderByGeneratedAtAsc(
        String location, LocalDateTime intervalStartUtc
    );

    List<PredictedPrice> findByModelVersionOrderByIntervalStartUtcAsc(String modelVersion);
}
