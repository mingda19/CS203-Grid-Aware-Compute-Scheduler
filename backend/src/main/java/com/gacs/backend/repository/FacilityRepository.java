package com.gacs.backend.repository;

import com.gacs.backend.model.Facility;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FacilityRepository extends JpaRepository<Facility, Long> {
    /** The one shared facility: the row with the lowest ID. */
    Optional<Facility> findFirstByOrderByIdAsc();

    /** The shared facility, locked for update. Callers lock this row only, never an owner's row after it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Facility f where f.id = (select min(g.id) from Facility g)")
    Optional<Facility> lockShared();
}
