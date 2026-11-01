package com.gacs.backend.service.constraintparse;

import com.fasterxml.jackson.annotation.JsonValue;

/** Job fields the constraint parser can fill, named as in the job API. */
public enum ConstraintField {
    ORIGINAL_START_TIME("originalStartTime"),
    DEADLINE("deadline"),
    FLEXIBILITY_WINDOW_HOURS("flexibilityWindowHours"),
    IS_EXEMPT("isExempt"),
    PRIORITY("priority");

    private final String apiName;

    ConstraintField(String apiName) {
        this.apiName = apiName;
    }

    @JsonValue
    public String apiName() {
        return apiName;
    }
}
