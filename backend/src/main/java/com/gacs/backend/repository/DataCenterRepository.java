package com.gacs.backend.repository;

import com.gacs.backend.model.DataCenter;
import com.gacs.backend.model.WeatherKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DataCenterRepository extends JpaRepository<DataCenter, WeatherKey> {
    List<DataCenter> findByLocation_LocationIdOrderByTimeAsc(Long locationId);

    List<DataCenter> findByLocation_LocationIdAndTimeBetweenOrderByTimeAsc(
        Long locationId, LocalDateTime start, LocalDateTime end
    );

    // Complete row for one location at one point in time.
    Optional<DataCenter> findByLocation_LocationIdAndTime(Long locationId, LocalDateTime time);

    // All locations' rows at one point in time.
    List<DataCenter> findByTime(LocalDateTime time);
}
