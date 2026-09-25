package com.gacs.backend.repository;

import com.gacs.backend.model.Solar;
import com.gacs.backend.model.WeatherKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SolarRepository extends JpaRepository<Solar, WeatherKey> {
    List<Solar> findByLocation_LocationIdOrderByTimeAsc(Long locationId);

    List<Solar> findByLocation_LocationIdAndTimeBetweenOrderByTimeAsc(
        Long locationId, LocalDateTime start, LocalDateTime end
    );

    // Complete row for one location at one point in time.
    Optional<Solar> findByLocation_LocationIdAndTime(Long locationId, LocalDateTime time);

    // All locations' rows at one point in time.
    List<Solar> findByTime(LocalDateTime time);
}
