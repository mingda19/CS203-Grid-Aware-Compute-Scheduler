package com.gacs.backend.service.constraintparse;

import java.math.BigDecimal;
import java.util.Map;

/** Reads the amount in phrases like "4 hours", "an hour", "twelve hours", "a few hours". */
final class Amounts {

    /** Regex for an amount: digits (optionally signed or decimal), number words, or vague words. */
    static final String NUMBER = "(-?\\d{1,15}(?:\\.\\d{1,6})?|a couple of|a couple|couple of|a few|few|several|"
            + "half an|half a|twenty[- ]four|thirty[- ]six|forty[- ]eight|seventy[- ]two|one|two|three|four|five|"
            + "six|seven|eight|nine|ten|eleven|twelve|fifteen|eighteen|twenty|thirty|an|a)";

    /** Regex for a unit; group 1 is the unit word. */
    static final String UNIT = "(minutes?|mins?|hours?|hrs?|h|days?|weeks?)\\b";

    /** Largest amount accepted, in hours (one year). Bigger numbers are reported, not used. */
    static final long MAX_HOURS = 24L * 366;

    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11),
            Map.entry("twelve", 12), Map.entry("fifteen", 15), Map.entry("eighteen", 18), Map.entry("twenty", 20),
            Map.entry("twenty-four", 24), Map.entry("thirty", 30), Map.entry("thirty-six", 36),
            Map.entry("forty-eight", 48), Map.entry("seventy-two", 72));

    private Amounts() {
    }

    /**
     * An amount converted to minutes, or the reason it can't be used.
     *
     * @param minutes   the amount in minutes; null when {@code problem} is set
     * @param problem   VAGUE_AMOUNT, OUT_OF_RANGE, or null
     */
    record Amount(BigDecimal minutes, AmbiguityReason problem) {
    }

    static Amount read(String number, String unit) {
        String n = number.trim().replace(' ', '-');
        BigDecimal value;
        if (n.startsWith("half-")) {
            value = new BigDecimal("0.5");
        } else if (n.matches("-?\\d.*")) {
            value = new BigDecimal(n);
        } else if (WORDS.containsKey(n)) {
            value = BigDecimal.valueOf(WORDS.get(n));
        } else {
            return new Amount(null, AmbiguityReason.VAGUE_AMOUNT);
        }
        BigDecimal minutesPerUnit = switch (unit.charAt(0)) {
            case 'm' -> BigDecimal.ONE;
            case 'd' -> BigDecimal.valueOf(24 * 60);
            case 'w' -> BigDecimal.valueOf(7 * 24 * 60);
            default -> BigDecimal.valueOf(60);
        };
        BigDecimal minutes = value.multiply(minutesPerUnit);
        if (minutes.abs().compareTo(BigDecimal.valueOf(MAX_HOURS * 60)) > 0) {
            return new Amount(null, AmbiguityReason.OUT_OF_RANGE);
        }
        return new Amount(minutes, null);
    }
}
