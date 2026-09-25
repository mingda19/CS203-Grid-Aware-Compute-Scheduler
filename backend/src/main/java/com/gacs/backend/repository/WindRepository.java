package com.gacs.backend.repository;

import com.gacs.backend.model.WeatherKey;
import com.gacs.backend.model.Wind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WindRepository extends JpaRepository<Wind, WeatherKey> {
    List<Wind> findByLocation_LocationIdOrderByTimeAsc(Long locationId);

    List<Wind> findByLocation_LocationIdAndTimeBetweenOrderByTimeAsc(
        Long locationId, LocalDateTime start, LocalDateTime end
    );

    // Complete row for one location at one point in time.
    Optional<Wind> findByLocation_LocationIdAndTime(Long locationId, LocalDateTime time);

    // All locations' rows at one point in time.
    List<Wind> findByTime(LocalDateTime time);
}
