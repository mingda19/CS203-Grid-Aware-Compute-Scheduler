package com.gacs.backend.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.gacs.backend.model.ElectricalPrice;
import com.gacs.backend.model.SppKey;

@Repository
public interface ElectricalPriceRepository extends JpaRepository<ElectricalPrice, SppKey> {
    @Query("select max(p.intervalStartUtc) from ElectricalPrice p")
    LocalDateTime findLatestIntervalStartUtc();

    @Query("select max(p.intervalStartUtc) from ElectricalPrice p where p.location = :location")
    LocalDateTime findLatestIntervalStartUtcForLocation(@Param("location") String location);

    /** Rows of {@code [hourOfDay (0-23), averagePriceUsdMwh]} over {@code [start, end)}. */
    @Query("select extract(hour from p.intervalStartUtc), avg(p.sppUsdMwh) from ElectricalPrice p " +
        "where p.location = :location and p.intervalStartUtc >= :start and p.intervalStartUtc < :end " +
        "and p.sppUsdMwh is not null group by extract(hour from p.intervalStartUtc)")
    List<Object[]> averageByHourOfDay(
        @Param("location") String location, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

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
