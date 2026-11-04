package com.gacs.backend.model;

/**
 * The kind of workload a compute job represents, matching the categories the workload-schedule
 * calendar view already colors and filters by (bug 6: operators previously had no way to say
 * what a job is, so every applied schedule entry guessed "ML Training" or "HPC Batch" purely from
 * whether the job used any GPU hours - see {@code ScheduleApplicationService}).
 *
 * <p>{@code label()} is the exact string the frontend's {@code WorkloadType} union and
 * {@code TYPE_CONFIG} (GoogleCalendarView.tsx) use, so a job's category can be written straight
 * into {@code WorkloadSchedule.type} once it is dispatched to the calendar.
 */
public enum WorkloadCategory {
    ML_TRAINING("ML Training"),
    CRYPTO_MINING("Crypto Mining"),
    HPC_BATCH("HPC Batch"),
    HVAC_PRE_COOL("HVAC Pre-Cool"),
    GRID_ALERT("Grid Alert");

    private final String label;

    WorkloadCategory(String label) {
        this.label = label;
    }

    /** The exact display string the calendar view's {@code WorkloadType} union expects. */
    public String label() {
        return label;
    }

    /**
     * Resolves a WorkloadCategory from either its enum name ("ML_TRAINING") or its calendar label
     * ("ML Training"), the way a request body may plausibly supply either. Null or blank defaults
     * to ML_TRAINING, the pre-existing implicit default this field replaces.
     */
    public static WorkloadCategory fromString(String value) {
        if (value == null || value.isBlank()) {
            return ML_TRAINING;
        }
        String trimmed = value.trim();
        for (WorkloadCategory category : values()) {
            if (category.name().equalsIgnoreCase(trimmed) || category.label.equalsIgnoreCase(trimmed)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Invalid workloadCategory: '" + value
                + "'. Allowed categories: ML_TRAINING, CRYPTO_MINING, HPC_BATCH, HVAC_PRE_COOL, GRID_ALERT");
    }
}
