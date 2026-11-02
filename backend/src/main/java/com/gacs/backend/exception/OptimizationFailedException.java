package com.gacs.backend.exception;

/** The solver threw (HTTP 503). The run is recorded as FAILED under {@link #getRunId()} with no plan to approve. */
public class OptimizationFailedException extends RuntimeException {

    private final String runId;
    private final String snapshot;

    public OptimizationFailedException(String runId, String snapshot, Throwable cause) {
        super("Optimiser run " + runId + " failed", cause);
        this.runId = runId;
        this.snapshot = snapshot;
    }

    public String getRunId() { return runId; }

    public String getSnapshot() { return snapshot; }
}
