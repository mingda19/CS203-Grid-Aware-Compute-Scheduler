package com.gacs.backend.repository;

import com.gacs.backend.model.FuelPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FuelPriceRepository extends JpaRepository<FuelPrice, LocalDateTime> {
    @Query("select max(p.period) from FuelPrice p")
    LocalDateTime findLatestPeriod();

    List<FuelPrice> findByPeriodBetweenOrderByPeriodAsc(LocalDateTime start, LocalDateTime end);
}
