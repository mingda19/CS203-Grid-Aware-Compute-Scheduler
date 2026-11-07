package com.gacs.backend.service.allocation;

import java.util.Locale;

/**
 * Why a job was left out of a plan, as a category rather than a sentence.
 *
 * <p>{@link JobOutcome#reason()} carries prose written for one specific job - it names that job's
 * own hours and deadline, so no two are alike. The run summary needs the opposite: one line per
 * <em>kind</em> of problem, with a count ("5 jobs could not be scheduled: their scheduling window
 * has passed"). This supplies that grouping key, and the plural phrasing the summary line uses.
 *
 * <p>Derived from the prose rather than carried alongside it, because {@link JobOutcome} is
 * constructed in nearly forty places and a sixth component would be noise in all but one of them.
 * The coupling that creates - this must know how {@link AllocationResult} words its reasons - is
 * held in {@link #prefix} and pinned by a test asserting every message that class can produce maps
 * to something other than {@link #OTHER}, so the two cannot drift apart silently.
 */
public enum UnscheduledReason {

    /** Nothing of the job's window remains: the deadline itself is behind the current hour. */
    DEADLINE_PASSED("Deadline ", "their deadline has already passed"),

    /** The deadline is ahead, but every hour the job could have started in is behind us. */
    WINDOW_ELAPSED("Window elapsed", "their scheduling window has passed"),

    /** The window is ahead of where the price forecast reaches, so there is no priced hour to use. */
    BEYOND_FORECAST("Beyond the price forecast", "no published price forecast covers their window yet"),

    /** Hours remain, but too few for the work to finish by the deadline. */
    NOT_ENOUGH_TIME("Not enough time left", "too little time remains before their deadline"),

    /** Hours remain and are priced, but other jobs hold the capacity. */
    CAPACITY_CONTENTION("Cluster full", "other jobs hold the capacity they need"),

    /** Anything unrecognised. A summary line still appears; it just cannot say why. */
    OTHER("", "they could not be placed");

    private final String prefix;
    private final String plural;

    UnscheduledReason(String prefix, String plural) {
        this.prefix = prefix;
        this.plural = plural;
    }

    /** How the summary finishes the line "N job(s) could not be scheduled: ...". */
    public String plural() {
        return plural;
    }

    /** The category {@code reason} belongs to, or {@link #OTHER} when nothing matches. */
    public static UnscheduledReason of(String reason) {
        if (reason == null || reason.isBlank()) {
            return OTHER;
        }
        String text = reason.strip().toLowerCase(Locale.ROOT);
        for (UnscheduledReason candidate : values()) {
            if (candidate != OTHER && text.startsWith(candidate.prefix.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return OTHER;
    }
}
