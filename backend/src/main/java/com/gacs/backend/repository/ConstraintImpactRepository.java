package com.gacs.backend.repository;

import com.gacs.backend.model.ConstraintImpact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConstraintImpactRepository extends JpaRepository<ConstraintImpact, Long> {

    List<ConstraintImpact> findByScheduleItemIdOrderByIdAsc(Long scheduleItemId);
}
