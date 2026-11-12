package com.gacs.backend.model;

import com.gacs.backend.service.allocation.ConstraintType;

/** A confirmed relationship, stated relative to the job holding it. */
public record JobConstraint(ConstraintType type, Long referencedJobId) { }
