package com.gacs.backend.service.constraintparse;

import java.time.ZonedDateTime;

/** Turns an operator's plain-English constraints into job fields (docs/constraint-parser-plan.md). */
public interface ConstraintParser {

    /**
     * @param text the operator's text, as typed
     * @param now  the current time in the operator's zone; relative phrases ("tomorrow", "in 3 hours") and
     *             clock times are read in that zone
     */
    ParsedConstraints parse(String text, ZonedDateTime now);
}
