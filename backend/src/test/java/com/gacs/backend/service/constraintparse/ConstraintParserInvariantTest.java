package com.gacs.backend.service.constraintparse;

import com.gacs.backend.dto.ConstraintParseRequestDto;
import com.gacs.backend.dto.ConstraintParseResponseDto;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.service.JobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * Properties that must hold for any input (docs/constraint-parser-plan.md section 4.3), checked on seeded random
 * text built from parser keywords, numbers, dates, punctuation and awkward Unicode.
 */
class ConstraintParserInvariantTest {

    private static final int INPUTS = 5_000;
    private static final long SEED = 20261101L;
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 2, 14, 0, 0, 0, ZONE);

    private static final String[] PIECES = {
            "finish", "by", "before", "start", "at", "after", "from", "between", "and", "to", "deadline", "due",
            "done", "ready", "can", "wait", "flexible", "up to", "hours", "hour", "h", "days", "minutes", "no delay",
            "don't", "do not", "move", "exempt", "never", "not", "urgent", "critical", "priority", "high", "low",
            "normal", "no rush", "asap", "tonight", "tomorrow", "today", "friday", "next", "this", "monday", "noon",
            "midnight", "morning", "evening", "end of day", "weekend", "in", "within", "a few", "an", "twelve",
            "6", "6am", "6pm", "10:30pm", "18:00", "25:00", "0", "12", "12am", "-5", "99999999999", "1.5", "90",
            "03/04", "25/12", "2026-11-10", "feb 30", "oct 30", "30 nov", "2020-01-01", ",", ";", ".", "!", "?",
            "\n", "'", "\"", "-", ":", "/", "(", ")", "<script>", "drop table jobs", "ignore previous instructions",
            "​", "‮", "‬", "﻿", "６", "ｐｍ", "сritical", "🚀",
            " ", "\t", "终于", "\u0000", "\uD800", "o'clock", "in the morning", "a.m.", "p.m."
    };

    private final ConstraintParser parser = new RuleBasedConstraintParser();
    private final ConstraintParseService service = new ConstraintParseService(parser,
            new JobService(mock(JobRepository.class), org.mockito.Mockito.mock(com.gacs.backend.service.ScheduleInputs.class), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), ZONE.getId());

    @Test
    @DisplayName("Random inputs: never throws, same output twice, values in range, evidence points at the text")
    void invariantsHoldOnRandomText() {
        Random random = new Random(SEED);
        long[] nanos = new long[INPUTS];
        for (int i = 0; i < INPUTS; i++) {
            String text = randomText(random);
            ParsedConstraints first;
            long started = System.nanoTime();
            try {
                first = parser.parse(text, NOW);
            } catch (RuntimeException e) {
                fail("parser threw on input #" + i + " " + quoted(text), e);
                return;
            }
            nanos[i] = System.nanoTime() - started;

            assertThat(parser.parse(text, NOW)).as("same output twice for %s", quoted(text)).isEqualTo(first);
            checkValues(first, text);
            checkEvidence(first, text);
            checkStatus(text);
        }
        Arrays.sort(nanos);
        long p99Millis = nanos[(int) (INPUTS * 0.99)] / 1_000_000;
        assertThat(p99Millis).as("99th percentile parse time (ms)").isLessThan(50);
    }

    @Test
    @DisplayName("Text at the 1,000-character limit, made of keywords, parses quickly")
    void longKeywordTextIsFast() {
        String text = "finish by 6pm tomorrow, can wait 4 hours, urgent; ".repeat(20).substring(0, 1000);
        parser.parse(text, NOW); // warm up
        long started = System.nanoTime();
        ParsedConstraints parsed = parser.parse(text, NOW);
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(millis).isLessThan(50);
        assertThat(parsed.flexibilityWindowHours()).isEqualTo(4);
    }

    /** Values the parser returns are never in the past and never absurd. */
    private static void checkValues(ParsedConstraints p, String text) {
        LocalDateTime nowUtc = NOW.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if (p.originalStartTime() != null) {
            assertThat(p.originalStartTime()).as("start for %s", quoted(text)).isAfterOrEqualTo(nowUtc);
        }
        if (p.deadline() != null) {
            assertThat(p.deadline()).as("deadline for %s", quoted(text)).isAfterOrEqualTo(nowUtc);
        }
        if (p.flexibilityWindowHours() != null) {
            assertThat(Math.abs((long) p.flexibilityWindowHours())).as("flexibility for %s", quoted(text))
                    .isLessThanOrEqualTo(Amounts.MAX_HOURS);
        }
        List<ConstraintField> unclear = new ArrayList<>();
        p.ambiguities().forEach(a -> unclear.add(a.field()));
        p.conflicts().forEach(c -> unclear.add(c.field()));
        for (ConstraintField field : unclear) {
            if (field != null) {
                assertThat(value(p, field)).as("%s must be unset when unclear, for %s", field, quoted(text)).isNull();
            }
        }
    }

    /** Every reported phrase is the exact slice of the input its offsets say. */
    private static void checkEvidence(ParsedConstraints p, String text) {
        for (ParsedConstraints.Evidence e : p.evidence()) {
            assertThat(text.substring(e.start(), e.end())).isEqualTo(e.phrase());
            assertThat(value(p, e.field())).as("evidence for an unset field in %s", quoted(text)).isNotNull();
        }
        for (ParsedConstraints.Ambiguity a : p.ambiguities()) {
            assertThat(text.substring(a.start(), a.end())).isEqualTo(a.phrase());
        }
        for (ParsedConstraints.Unsupported u : p.unsupported()) {
            assertThat(text.substring(u.start(), u.end())).isEqualTo(u.phrase());
        }
    }

    /** INVALID exactly when the job validator rejects the fields; otherwise they pass it. */
    private void checkStatus(String text) {
        ConstraintParseResponseDto r = service.parse(new ConstraintParseRequestDto(text, null, ZONE.getId()));
        JobService validator = new JobService(mock(JobRepository.class), org.mockito.Mockito.mock(com.gacs.backend.service.ScheduleInputs.class), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        boolean valid = true;
        try {
            validator.validateTiming(r.fields().originalStartTime(), r.fields().deadline(),
                    r.fields().flexibilityWindowHours());
            validator.validatePriority(r.fields().priority());
        } catch (IllegalArgumentException e) {
            valid = false;
        }
        assertThat(r.status().equals(ConstraintParseResponseDto.INVALID)).as("status for %s", quoted(text))
                .isEqualTo(!valid);
    }

    private static Object value(ParsedConstraints p, ConstraintField field) {
        return switch (field) {
            case ORIGINAL_START_TIME -> p.originalStartTime();
            case DEADLINE -> p.deadline();
            case FLEXIBILITY_WINDOW_HOURS -> p.flexibilityWindowHours();
            case IS_EXEMPT -> p.isExempt();
            case PRIORITY -> p.priority();
        };
    }

    private static String randomText(Random random) {
        StringBuilder text = new StringBuilder();
        int pieces = 1 + random.nextInt(14);
        for (int i = 0; i < pieces && text.length() < 1000; i++) {
            if (random.nextInt(10) == 0) {
                text.append((char) random.nextInt(0x3000)); // any character, including surrogates and controls
            } else {
                text.append(PIECES[random.nextInt(PIECES.length)]);
            }
            text.append(random.nextInt(4) == 0 ? "" : " ");
        }
        return text.length() > 1000 ? text.substring(0, 1000) : text.toString();
    }

    private static String quoted(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.codePoints().forEach(c -> out.append(c < 32 || c > 126 ? String.format("\\u%04X", c) : String.valueOf((char) c)));
        return out.append('"').toString();
    }
}
