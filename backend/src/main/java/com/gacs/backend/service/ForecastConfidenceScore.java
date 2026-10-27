package com.gacs.backend.service;

public final class ForecastConfidenceScore {

    private ForecastConfidenceScore() {}

    public static Double fromUncertaintyRatio(Double ratio) {
        if (ratio == null || !Double.isFinite(ratio) || ratio < 0) {
            return null;
        }
        return 1.0 / (1.0 + ratio);
    }
}
