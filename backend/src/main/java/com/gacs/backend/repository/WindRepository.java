package com.gacs.backend.repository;

import com.gacs.backend.model.WeatherKey;
import com.gacs.backend.model.Wind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WindRepository extends JpaRepository<Wind, WeatherKey> {
    List<Wind> findByLocation_LocationIdOrderByTimeAsc(Long locationId);

    List<Wind> findByLocation_LocationIdAndTimeBetweenOrderByTimeAsc(
        Long locationId, LocalDateTime start, LocalDateTime end
    );
}
