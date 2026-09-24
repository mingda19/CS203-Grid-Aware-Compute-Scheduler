package com.gacs.backend.repository;

import com.gacs.backend.model.LoadData;
import com.gacs.backend.model.WeatherKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LoadDataRepository extends JpaRepository<LoadData, WeatherKey> {
    List<LoadData> findByLocation_LocationIdOrderByTimeAsc(Long locationId);

    List<LoadData> findByLocation_LocationIdAndTimeBetweenOrderByTimeAsc(
        Long locationId, LocalDateTime start, LocalDateTime end
    );
}
