package com.gacs.backend.repository;

import com.gacs.backend.model.OptimizerRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OptimizerRunRepository extends JpaRepository<OptimizerRun, String> {
}
