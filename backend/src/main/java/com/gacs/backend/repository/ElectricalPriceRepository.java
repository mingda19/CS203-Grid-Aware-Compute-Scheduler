package com.gacs.backend.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.gacs.backend.model.ElectricalPrice;
import com.gacs.backend.model.SppKey;

@Repository
public interface ElectricalPriceRepository extends JpaRepository<ElectricalPrice, SppKey> {
    List<ElectricalPrice> findByLocationOrderByIntervalStartUtcAsc(String location);

    List<ElectricalPrice> findByLocationAndIntervalStartUtcBetweenOrderByIntervalStartUtcAsc(
        String location, LocalDateTime start, LocalDateTime end
    );

    List<ElectricalPrice> findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAsc(
        String location, LocalDateTime start, LocalDateTime end, Pageable pageable
    );

    long countByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThan(
        String location, LocalDateTime start, LocalDateTime end
    );

    List<ElectricalPrice> findByIntervalStartUtc(LocalDateTime intervalStartUtc);
}
