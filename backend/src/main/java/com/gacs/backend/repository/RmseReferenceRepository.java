package com.gacs.backend.repository;

import com.gacs.backend.model.RmseReference;
import com.gacs.backend.model.RmseReferenceKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RmseReferenceRepository extends JpaRepository<RmseReference, RmseReferenceKey> {
    List<RmseReference> findByModelKind(String modelKind);
}
