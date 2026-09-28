package com.gacs.backend.repository;

import com.gacs.backend.model.HourlyDemandForecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HourlyDemandForecastRepository extends JpaRepository<HourlyDemandForecast, LocalDateTime> {
    @Query("select max(p.period) from HourlyDemandForecast p")
    LocalDateTime findLatestPeriod();

    List<HourlyDemandForecast> findByPeriodBetweenOrderByPeriodAsc(LocalDateTime start, LocalDateTime end);
}
