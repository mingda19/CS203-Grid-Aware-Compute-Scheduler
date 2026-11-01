package com.gacs.backend.model;

/**
 * Priority levels for compute workloads.
 * Used for tie-breaking when jobs compete for capacity (GRID-155 / GRID-124).
 */
public enum JobPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW;

    /**
     * Resolves a JobPriority from string safely.
     * Null or blank defaults to NORMAL. Unknown values throw an IllegalArgumentException.
     */
    public static JobPriority fromString(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        try {
            return JobPriority.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid priority: '" + value + "'. Allowed priorities: CRITICAL, HIGH, NORMAL, LOW");
        }
    }
}
