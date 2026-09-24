package com.gacs.backend.repository;

import com.gacs.backend.model.Solar;
import com.gacs.backend.model.WeatherKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SolarRepository extends JpaRepository<Solar, WeatherKey> {
    List<Solar> findByLocation_LocationIdOrderByTimeAsc(Long locationId);

    List<Solar> findByLocation_LocationIdAndTimeBetweenOrderByTimeAsc(
        Long locationId, LocalDateTime start, LocalDateTime end
    );
}
