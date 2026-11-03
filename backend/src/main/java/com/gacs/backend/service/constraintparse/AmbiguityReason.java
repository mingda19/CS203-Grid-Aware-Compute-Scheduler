package com.gacs.backend.service.constraintparse;

/**
 * Why a phrase was not turned into a value. The parser never guesses: the field is left unset and the
 * operator is asked (docs/constraint-parser-plan.md section 3.3).
 */
public enum AmbiguityReason {
    /** A clock time without am/pm, e.g. "by 6". */
    AM_OR_PM,
    /** "this Friday" / "next Friday": which week is meant? */
    WHICH_WEEK,
    /** A weekday that is also today, e.g. "Friday" said on a Friday. */
    TODAY_OR_NEXT_WEEK,
    /** A numeric date that reads differently day-first and month-first, e.g. "03/04". */
    DATE_FORMAT,
    /** A date without a time, e.g. "by tomorrow". */
    TIME_MISSING,
    /** Words like "end of day", "this weekend", "evening". */
    VAGUE_TIME,
    /** "midnight" or "12am" next to a date: the start or the end of that day? */
    MIDNIGHT_WHICH_DAY,
    /** The time has already passed. */
    IN_THE_PAST,
    /** A date that does not exist, e.g. "Feb 30". */
    INVALID_DATE,
    /** A time that does not exist, e.g. "25:00". */
    INVALID_TIME,
    /** A local time skipped by a daylight-saving change. */
    DST_GAP,
    /** A local time that happens twice because of a daylight-saving change. */
    DST_OVERLAP,
    /** "flexible" or "can wait" without saying how long. */
    FLEXIBILITY_AMOUNT_MISSING,
    /** "a few hours", "a couple of hours". */
    VAGUE_AMOUNT,
    /** Flexibility that is not a whole number of hours, e.g. "90 minutes". */
    NOT_WHOLE_HOURS,
    /** A number too large to be meant, e.g. "99999999999 hours". */
    OUT_OF_RANGE,
    /** "ASAP": high priority, or start now? */
    ASAP,
    /** Says what the job is not ("not critical"), not what it is. */
    NEGATED
}
