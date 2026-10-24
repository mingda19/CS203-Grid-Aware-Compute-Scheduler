package com.gacs.backend.service.allocation;

/**
 * CPU-hours and GPU-hours available in one slot (contract section 6.4, Capacity / AC3).
 */
public record CapacityLimit(double cpuHours, double gpuHours) {

    public CapacityLimit {
        if (!Double.isFinite(cpuHours) || cpuHours < 0.0) {
            throw new IllegalArgumentException("cpuHours capacity must be a finite value >= 0");
        }
        if (!Double.isFinite(gpuHours) || gpuHours < 0.0) {
            throw new IllegalArgumentException("gpuHours capacity must be a finite value >= 0");
        }
    }

    /**
     * The largest share of {@code job}'s work that fits in this capacity: the tighter of
     * {@code cpuHours / job.cpuHours} and {@code gpuHours / job.gpuHours}, ignoring a resource the
     * job does not use. Not capped at 1; callers cap it at the work still left.
     */
    public double maxShare(OptimizerJob job) {
        double share = Double.POSITIVE_INFINITY;
        if (job.cpuHours() > 0.0) {
            share = Math.min(share, cpuHours / job.cpuHours());
        }
        if (job.gpuHours() > 0.0) {
            share = Math.min(share, gpuHours / job.gpuHours());
        }
        return share;
    }

    /** What is left after using {@code cpu} and {@code gpu} hours, never below 0. */
    public CapacityLimit minus(double cpu, double gpu) {
        return new CapacityLimit(Math.max(0.0, cpuHours - cpu), Math.max(0.0, gpuHours - gpu));
    }
}
