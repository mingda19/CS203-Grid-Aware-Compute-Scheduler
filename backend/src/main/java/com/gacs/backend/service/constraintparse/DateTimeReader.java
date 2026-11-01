package com.gacs.backend.service.constraintparse;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one date/time expression at a position in normalised text: "6am tomorrow", "Friday 17:00",
 * "Oct 30 9am", "in 3 hours", "tonight at 11". Times are read in the operator's zone and returned in UTC.
 * Anything that could mean more than one thing comes back as an ambiguity instead of a value.
 */
final class DateTimeReader {

    /**
     * What was read.
     *
     * @param end       end of the expression in the normalised text
     * @param utc       the time in UTC, or null when {@code problem} is set
     * @param problem   why there is no value, or null
     * @param message   question for the operator when {@code problem} is set
     */
    record Reading(int end, LocalDateTime utc, AmbiguityReason problem, String message) {
    }

    private static final int MAX_TOKENS = 5;
    private static final String MONTH = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|"
            + "aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

    private static final Pattern FILLER = Pattern.compile("(?:at|on|the|of|around|about|approx(?:imately)?)\\b\\s*");
    private static final Pattern RELATIVE = Pattern.compile("(?:in|within)\\s+" + Amounts.NUMBER + "\\s*" + Amounts.UNIT);
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\d])");
    private static final Pattern SLASH_DATE = Pattern.compile("(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?(?![\\d:/])");
    private static final Pattern DAY_MONTH = Pattern.compile(
            "(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH + "\\b\\.?(?:,?\\s+(\\d{4})\\b)?");
    private static final Pattern MONTH_DAY = Pattern.compile(
            MONTH + "\\b\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?![\\d:])(?:,?\\s+(\\d{4})\\b)?");
    private static final Pattern DAY_AFTER = Pattern.compile("day after tomorrow\\b");
    private static final Pattern RELATIVE_DAY = Pattern.compile(
            "(today|tonight|tomorrow|tmrw|tmr|tomorow|tommorow|tommorrow)\\b");
    private static final Pattern WEEKDAY = Pattern.compile("(?:(this|next|coming|following)\\s+)?"
            + "(monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun)\\b");
    private static final Pattern TIME_12H = Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])");
    private static final Pattern TIME_24H = Pattern.compile("(\\d{1,2}):(\\d{2})(?![\\d:])(?:\\s*(?:hrs|h)\\b)?");
    private static final Pattern NAMED_TIME = Pattern.compile("(noon|midday|midnight)\\b");
    private static final Pattern O_CLOCK = Pattern.compile("(\\d{1,2})\\s*o'?clock\\b");
    private static final Pattern PART_OF_DAY = Pattern.compile(
            "(?:this |in the |tomorrow )?(morning|afternoon|evening)\\b|at night\\b");
    private static final Pattern VAGUE = Pattern.compile("(?:end of (?:the )?(?:business )?(?:day|week|month)|eod|eow|cob|"
            + "close of business|(?:this |the |over the )?weekend|later today|later|soon|sometime|some time)\\b");
    private static final Pattern BARE_HOUR = Pattern.compile("(\\d{1,2})(?![\\d:/\\-a-z%]|\\.\\d|\\s*(?:h|hrs?|hours?|mins?|"
            + "minutes?|days?|weeks?|gpus?|cpus?|jobs?|nodes?|cores?|kwh|mw|percent)\\b)");

    private static final Map<String, DayOfWeek> WEEKDAYS = Map.ofEntries(
            Map.entry("mon", DayOfWeek.MONDAY), Map.entry("tue", DayOfWeek.TUESDAY), Map.entry("wed", DayOfWeek.WEDNESDAY),
            Map.entry("thu", DayOfWeek.THURSDAY), Map.entry("fri", DayOfWeek.FRIDAY), Map.entry("sat", DayOfWeek.SATURDAY),
            Map.entry("sun", DayOfWeek.SUNDAY));

    private DateTimeReader() {
    }

    /**
     * Reads an expression starting at {@code from} (after skipping filler words), stopping at {@code limit}.
     *
     * @param now   the current time in the operator's zone
     * @param after a date-less time ("6am") is the first such time after this one (local, operator's zone);
     *              usually now, or the job's start for a deadline
     * @return the reading, or null when no date or time starts here
     */
    static Reading read(String text, int from, int limit, ZonedDateTime now, LocalDateTime after) {
        State s = new State(text, limit, now);
        int position = from;
        for (int tokens = 0; tokens < MAX_TOKENS; tokens++) {
            position = skip(text, position, limit);
            int next = s.token(position);
            if (next < 0) {
                break;
            }
            position = next;
            s.end = next;
            if (s.problem != null) {
                break;
            }
        }
        if (s.end < 0) {
            return null;
        }
        return s.resolve(after);
    }

    private static int skip(String text, int position, int limit) {
        while (position < limit && text.charAt(position) == ' ') {
            position++;
        }
        Matcher filler = FILLER.matcher(text).region(position, limit);
        while (filler.lookingAt()) {
            position = filler.end();
            filler.region(position, limit);
        }
        return position;
    }

    /** One expression being read. */
    private static final class State {
        private final String text;
        private final int limit;
        private final ZonedDateTime now;
        private final LocalDate today;

        int end = -1;
        LocalDate date;
        LocalTime time;
        boolean midnight;
        boolean ambiguousHour;
        int ambiguousHourValue;
        String partOfDay;
        boolean tonight;
        LocalDateTime relative;
        AmbiguityReason problem;
        String message;

        State(String text, int limit, ZonedDateTime now) {
            this.text = text;
            this.limit = limit;
            this.now = now;
            this.today = now.toLocalDate();
        }

        /** Reads one token at {@code at}; returns its end, or -1 when nothing more belongs to the expression. */
        int token(int at) {
            if (at >= limit) {
                return -1;
            }
            Matcher m;
            if (relative == null && date == null && time == null && (m = match(RELATIVE, at)) != null) {
                Amounts.Amount amount = Amounts.read(m.group(1), m.group(2));
                if (amount.problem() != null) {
                    return fail(amount.problem(), m, amount.problem() == AmbiguityReason.VAGUE_AMOUNT
                            ? "How many " + m.group(2) + " exactly?" : "That is too far ahead to be meant.");
                }
                long minutes = amount.minutes().setScale(0, java.math.RoundingMode.HALF_UP).longValue();
                boolean wholeDays = m.group(2).startsWith("d") || m.group(2).startsWith("w");
                if (wholeDays && amount.minutes().remainder(BigDecimal.valueOf(24 * 60)).signum() == 0) {
                    date = today.plusDays(minutes / (24 * 60));
                        relative = now.toLocalDateTime().plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES);
                } else {
                    relative = now.toLocalDateTime().plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES);
                }
                return m.end();
            }
            if (date == null) {
                int dateEnd = dateToken(at);
                if (dateEnd != -1) {
                    return dateEnd;
                }
            }
            if (time == null && !ambiguousHour && (relative == null || date != null)) {
                int timeEnd = timeToken(at);
                if (timeEnd != -1) {
                    return timeEnd;
                }
            }
            if (partOfDay == null && (m = match(PART_OF_DAY, at)) != null) {
                partOfDay = m.group(1) == null ? "evening" : m.group(1);
                return m.end();
            }
            if ((m = match(VAGUE, at)) != null) {
                return fail(AmbiguityReason.VAGUE_TIME, m, "\"" + m.group() + "\" is not a clock time. What time exactly?");
            }
            return -1;
        }

        private int dateToken(int at) {
            Matcher m;
            if ((m = match(ISO_DATE, at)) != null) {
                return setDate(m, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            if ((m = match(SLASH_DATE, at)) != null) {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                int year = m.group(3) == null ? -1 : year(m.group(3));
                if (a <= 12 && b <= 12 && a != b) {
                    return fail(AmbiguityReason.DATE_FORMAT, m, "Is \"" + m.group() + "\" day/month or month/day?");
                }
                // One part above 12 can only be the day; equal parts read the same either way.
                return a > 12 || a == b ? setDate(m, year, b, a) : setDate(m, year, a, b);
            }
            if ((m = match(DAY_MONTH, at)) != null) {
                return setDate(m, m.group(3) == null ? -1 : Integer.parseInt(m.group(3)), month(m.group(2)),
                        Integer.parseInt(m.group(1)));
            }
            if ((m = match(MONTH_DAY, at)) != null) {
                return setDate(m, m.group(3) == null ? -1 : Integer.parseInt(m.group(3)), month(m.group(1)),
                        Integer.parseInt(m.group(2)));
            }
            if ((m = match(DAY_AFTER, at)) != null) {
                date = today.plusDays(2);
                return m.end();
            }
            if ((m = match(RELATIVE_DAY, at)) != null) {
                String word = m.group(1);
                date = word.startsWith("to") && !word.startsWith("tom") ? today : today.plusDays(1);
                tonight = word.equals("tonight");
                return m.end();
            }
            if ((m = match(WEEKDAY, at)) != null) {
                DayOfWeek target = WEEKDAYS.get(m.group(2).substring(0, 3));
                int ahead = (target.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
                String which = m.group(1);
                if ("next".equals(which) || "following".equals(which)) {
                    return fail(AmbiguityReason.WHICH_WEEK, m, "Does \"" + m.group() + "\" mean the coming "
                            + capitalised(target) + " or the one after?");
                }
                if (ahead == 0 && !"coming".equals(which)) {
                    return fail(AmbiguityReason.TODAY_OR_NEXT_WEEK, m, "Today is " + capitalised(target)
                            + ". Do you mean today or next week?");
                }
                date = today.plusDays(ahead == 0 ? 7 : ahead);
                return m.end();
            }
            return -1;
        }

        private int timeToken(int at) {
            Matcher m;
            if ((m = match(TIME_12H, at)) != null) {
                int hour = Integer.parseInt(m.group(1));
                int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                if (hour < 1 || hour > 12 || minute > 59) {
                    return fail(AmbiguityReason.INVALID_TIME, m, "\"" + m.group() + "\" is not a valid time.");
                }
                boolean pm = m.group(3).startsWith("p");
                midnight = !pm && hour == 12 && minute == 0;
                time = LocalTime.of(hour % 12 + (pm ? 12 : 0), minute);
                return m.end();
            }
            if ((m = match(TIME_24H, at)) != null) {
                int hour = Integer.parseInt(m.group(1));
                int minute = Integer.parseInt(m.group(2));
                if (hour > 23 || minute > 59) {
                    return fail(AmbiguityReason.INVALID_TIME, m, "\"" + m.group() + "\" is not a valid time.");
                }
                time = LocalTime.of(hour, minute);
                midnight = hour == 0 && minute == 0;
                return m.end();
            }
            if ((m = match(NAMED_TIME, at)) != null) {
                midnight = m.group(1).equals("midnight");
                time = midnight ? LocalTime.MIDNIGHT : LocalTime.NOON;
                return m.end();
            }
            if ((m = match(O_CLOCK, at)) != null) {
                return hourOnly(m, Integer.parseInt(m.group(1)));
            }
            if ((m = match(BARE_HOUR, at)) != null) {
                int hour = Integer.parseInt(m.group(1));
                return hour > 23 ? -1 : hourOnly(m, hour);
            }
            return -1;
        }

        /** "by 6", "6 o'clock": 13-23 is clear; 0-12 needs am/pm from context or the operator. */
        private int hourOnly(Matcher m, int hour) {
            if (hour > 23) {
                return fail(AmbiguityReason.INVALID_TIME, m, "\"" + m.group() + "\" is not a valid time.");
            }
            if (hour >= 13 || hour == 0) {
                time = LocalTime.of(hour, 0);
                midnight = hour == 0;
            } else {
                ambiguousHour = true;
                ambiguousHourValue = hour;
            }
            return m.end();
        }

        private int setDate(Matcher m, int year, int month, int day) {
            try {
                LocalDate candidate = LocalDate.of(year < 0 ? today.getYear() : year, month, day);
                if (year < 0 && candidate.isBefore(today)) {
                    candidate = candidate.plusYears(1);
                }
                date = candidate;
                return m.end();
            } catch (DateTimeException e) {
                return fail(AmbiguityReason.INVALID_DATE, m, "\"" + m.group() + "\" is not a valid date.");
            }
        }

        private int fail(AmbiguityReason reason, Matcher m, String question) {
            problem = reason;
            message = question;
            return m.end();
        }

        private Matcher match(Pattern pattern, int at) {
            Matcher m = pattern.matcher(text).region(at, limit);
            return m.lookingAt() ? m : null;
        }

        Reading resolve(LocalDateTime after) {
            if (problem != null) {
                return new Reading(end, null, problem, message);
            }
            if (ambiguousHour) {
                boolean evening = tonight || "evening".equals(partOfDay) || "afternoon".equals(partOfDay);
                boolean morning = "morning".equals(partOfDay);
                if ((evening || morning) && ambiguousHourValue != 12) {
                    time = LocalTime.of(ambiguousHourValue % 12 + (evening ? 12 : 0), 0);
                } else {
                    return new Reading(end, null, AmbiguityReason.AM_OR_PM,
                            "Did you mean " + ambiguousHourValue + "am or " + ambiguousHourValue + "pm?");
                }
            }
            LocalDateTime local;
            if (relative != null && time == null) {
                local = relative;
            } else if (time == null) {
                if (date == null) {
                    return new Reading(end, null, AmbiguityReason.VAGUE_TIME,
                            "\"" + partOfDay + "\" is not a clock time. What time exactly?");
                }
                return new Reading(end, null, AmbiguityReason.TIME_MISSING,
                        (tonight ? "Tonight" : "On " + date) + " at what time?");
            } else if (date == null) {
                local = after.toLocalDate().atTime(time);
                if (!local.isAfter(after)) {
                    local = local.plusDays(1);
                }
            } else {
                if (midnight && !tonight) {
                    return new Reading(end, null, AmbiguityReason.MIDNIGHT_WHICH_DAY,
                            "Midnight at the start of " + date + " or at the end of it?");
                }
                local = date.atTime(time).plusDays(midnight ? 1 : 0);
            }
            return inZone(local);
        }

        private Reading inZone(LocalDateTime local) {
            ZoneId zone = now.getZone();
            int offsets = zone.getRules().getValidOffsets(local).size();
            if (offsets == 0) {
                return new Reading(end, null, AmbiguityReason.DST_GAP,
                        local + " does not exist in " + zone + " (clocks skip it). Which time do you mean?");
            }
            if (offsets > 1) {
                return new Reading(end, null, AmbiguityReason.DST_OVERLAP,
                        local + " happens twice in " + zone + " (clocks go back). Which one do you mean?");
            }
            ZonedDateTime zoned = local.atZone(zone);
            if (zoned.isBefore(now)) {
                return new Reading(end, null, AmbiguityReason.IN_THE_PAST, local + " has already passed.");
            }
            return new Reading(end, zoned.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(), null, null);
        }
    }

    private static int year(String digits) {
        int year = Integer.parseInt(digits);
        return digits.length() == 2 ? 2000 + year : year;
    }

    private static int month(String name) {
        return switch (name.substring(0, 3)) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            default -> 12;
        };
    }

    private static String capitalised(DayOfWeek day) {
        String name = day.name().toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
