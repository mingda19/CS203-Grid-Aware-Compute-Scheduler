package com.gacs.backend.repository;

import com.gacs.backend.model.LoadForecastDam;
import com.gacs.backend.model.LoadForecastDamKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LoadForecastDamRepository extends JpaRepository<LoadForecastDam, LoadForecastDamKey> {
    @Query("select max(l.intervalStartUtc) from LoadForecastDam l")
    LocalDateTime findLatestIntervalStartUtc();

    List<LoadForecastDam> findByZoneOrderByIntervalStartUtcAsc(String zone);

    List<LoadForecastDam> findByZoneAndIntervalStartUtcBetweenOrderByIntervalStartUtcAsc(
        String zone, LocalDateTime start, LocalDateTime end
    );
}
