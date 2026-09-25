package com.gacs.backend.repository;

import com.gacs.backend.model.FuelGenerationMonthly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FuelGenerationMonthlyRepository extends JpaRepository<FuelGenerationMonthly, LocalDateTime> {
    List<FuelGenerationMonthly> findByPeriodBetweenOrderByPeriodAsc(LocalDateTime start, LocalDateTime end);
}
