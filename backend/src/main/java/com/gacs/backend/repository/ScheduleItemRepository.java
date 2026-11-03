package com.gacs.backend.repository;

import com.gacs.backend.model.ScheduleItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduleItemRepository extends JpaRepository<ScheduleItem, Long> {

    List<ScheduleItem> findByScheduleRunIdOrderByIdAsc(Long scheduleRunId);
}
