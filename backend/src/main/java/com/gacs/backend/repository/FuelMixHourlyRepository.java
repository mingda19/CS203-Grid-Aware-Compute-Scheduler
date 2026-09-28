package com.gacs.backend.repository;

import com.gacs.backend.model.FuelMixHourly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FuelMixHourlyRepository extends JpaRepository<FuelMixHourly, LocalDateTime> {
    @Query("select max(p.period) from FuelMixHourly p")
    LocalDateTime findLatestPeriod();

    List<FuelMixHourly> findByPeriodBetweenOrderByPeriodAsc(LocalDateTime start, LocalDateTime end);
}
