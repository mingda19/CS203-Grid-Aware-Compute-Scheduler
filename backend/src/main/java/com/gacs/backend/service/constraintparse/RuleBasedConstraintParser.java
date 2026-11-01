package com.gacs.backend.service.constraintparse;

import com.gacs.backend.model.JobPriority;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Ambiguity;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Conflict;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Evidence;
import com.gacs.backend.service.constraintparse.ParsedConstraints.Unsupported;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, rule-based parser (docs/constraint-parser-plan.md section 2). It reads the text as data only:
 * nothing in it is followed as an instruction, and it can only ever fill the five supported fields.
 *
 * <p>Rules, in order, each claiming the part of the text it matched so later rules can't reuse it:
 * "ASAP", priority, exempt, flexibility, "between X and Y", start times, deadlines. Whatever is left over is
 * reported as unsupported. A field given two different values, or given an ambiguous one, is left unset.
 */
@Component
public class RuleBasedConstraintParser implements ConstraintParser {

    private static final String N = Amounts.NUMBER;
    private static final String U = Amounts.UNIT;

    private static final Pattern ASAP = Pattern.compile(
            "\\b(?:asap|a\\.s\\.a\\.p|as soon as possible|right away|immediately|straight away)\\b\\.?");

    private static final Pattern PRIORITY_LOW_PHRASE = Pattern.compile("\\b(?:not urgent|no rush|no hurry|"
            + "not important|not time[- ]?critical|low importance|whenever(?: possible)?|not a priority)\\b");
    private static final Pattern PRIORITY_NAMED = Pattern.compile("\\bpriority\\s*(?:is|:|=|of|level|to|should be|be)?"
            + "\\s*(?:set to\\s+)?(critical|high|normal|medium|standard|low)\\b");
    private static final Pattern PRIORITY_BEFORE_WORD = Pattern.compile(
            "\\b(critical|high|normal|medium|standard|low|top|highest|lowest)[\\s-]*priority\\b");
    private static final Pattern PRIORITY_WORD = Pattern.compile(
            "\\b(?:(?:mission|business|time)[- ])?(critical|urgent|important)\\b");

    private static final Pattern EXEMPT_NO = Pattern.compile("\\b(?:not|never|isn't|isnt|is not) exempt\\b|"
            + "\\bcan be (?:moved|rescheduled|shifted)\\b|\\b(?:ok|okay|fine|free) to (?:move|reschedule|shift)\\b|"
            + "\\bfeel free to (?:move|reschedule|shift)\\b");
    private static final Pattern EXEMPT_YES = Pattern.compile("\\b(?:please )?(?:do not|don't|dont|never|must not|"
            + "mustn't|cannot|can't|cant|should not|shouldn't)\\s+(?:move|reschedule|shift|touch|optimi[sz]e)\\b"
            + "(?:\\s+(?:this|the|it|my)(?:\\s+job)?)?|"
            + "\\bmust (?:run|start|execute) (?:as scheduled|on schedule|at the scheduled time|as planned)\\b|"
            + "\\b(?:keep|leave) (?:it |this |the job )?(?:at |on |in )?(?:the |its )?(?:original|scheduled|fixed|current) "
            + "(?:time|slot|schedule)\\b|\\bpinned\\b|\\bpin (?:it|this|the job)\\b|\\bfixed (?:time|slot|schedule)\\b|"
            + "\\bno reschedul(?:e|ing)\\b|\\bexempt\\b");

    private static final Pattern FLEX_ZERO = Pattern.compile("\\b(?:no (?:delay|delays|flexibility|flex|slack|leeway|"
            + "wiggle room)|zero (?:flexibility|flex|delay|slack)|can't wait|cant wait|cannot wait|can not wait|"
            + "(?:cannot|can't|cant|can not|must not|mustn't|should not|shouldn't) be delayed|must not delay|"
            + "do not delay|don't delay|dont delay|(?:not|isn't|isnt|is not) flexible|inflexible|"
            + "(?:must|has to|needs to|should) start on time|starts? on time)\\b");
    private static final List<Pattern> FLEX_AMOUNT = List.of(
            Pattern.compile("\\b(?:(?:can|could|may|is able to|able to|ok to|okay to|allowed to|happy to)\\s+)?"
                    + "(?:wait|be delayed|be postponed|be pushed(?: back)?|slip|be shifted|delay(?:ed)?|postpone(?:d)?|"
                    + "push(?:ed)?(?: back)?|shift(?:ed)?)\\s+(?:(?:by|for|up to|upto|at most|a maximum of|max(?:imum)?|"
                    + "no more than)\\s+)*" + N + "\\s*" + U),
            Pattern.compile("\\b(?:flexible|flexibility|flex|slack|leeway|buffer|wiggle room)\\s*(?:of|by|for|up to|is|:|=)?"
                    + "\\s*(?:up to\\s+|at most\\s+)?" + N + "\\s*" + U),
            Pattern.compile("(?<![a-z0-9])" + N + "\\s*" + U
                    + "\\s+(?:of\\s+)?(?:flexibility|flex|slack|leeway|buffer|delay|wiggle room)\\b"),
            Pattern.compile("\\bup to\\s+" + N + "\\s*" + U + "\\s+(?:late|later|of delay)\\b"));
    private static final Pattern FLEX_NO_AMOUNT = Pattern.compile("\\b(?:can wait|is flexible|flexible|"
            + "has some flexibility|some flexibility|can be delayed|can be postponed|can slip|not time[- ]sensitive|"
            + "can be late)\\b");

    private static final Pattern RANGE_START = Pattern.compile("\\b(?:between|from)\\s+");
    private static final Pattern RANGE_JOIN = Pattern.compile("\\s*(?:and|to|until|till|-)\\s*");
    private static final List<Pattern> START_ANCHORS = List.of(
            Pattern.compile("\\b(?:start(?:s|ing)?|begin(?:s|ning)?|kick(?:s)? off|launch(?:es)?|run(?:s|ning)?)\\b"
                    + "(?:\\s+(?:at|on|from|after|around|no earlier than|not before))?\\s*:?"),
            Pattern.compile("\\b(?:not before|no earlier than|not earlier than|after|from|earliest)\\b\\s*:?"));
    private static final List<Pattern> DEADLINE_ANCHORS = List.of(
            Pattern.compile("\\b(?:(?:must|should|needs? to|has to|have to)\\s+(?:be\\s+)?)?(?:finish(?:ed)?|"
                    + "complete(?:d)?|done|ready|due|end|wrap(?:ped)? up|deliver(?:ed)?)\\b"
                    + "(?:\\s+(?:by|before|at|on|no later than))?\\s*:?"),
            Pattern.compile("\\b(?:deadline(?:\\s+(?:is|of|at|on))?|no later than|not later than|latest|by|before)\\b\\s*:?"));

    private static final Pattern CLAUSE_BREAK = Pattern.compile(
            "[,;!?\\n]|\\.(?=\\s|$)|\\s(?:and|but|also|then|plus)\\s");
    private static final Pattern FILLER_ONLY = Pattern.compile(
            "(?:please|pls|plz|thanks|thank you|thx|ok|okay|note|fyi|hi|hello|and|but|also|then)");
    private static final Set<String> NEGATORS = Set.of("not", "no", "never", "don't", "dont", "isn't", "isnt",
            "without", "non", "nothing", "cannot", "can't", "cant");
    private static final DateTimeFormatter API_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    public ParsedConstraints parse(String text, ZonedDateTime now) {
        return new Run(TextNormalizer.normalize(text == null ? "" : text), now).parse();
    }

    /** One parse: the text, what has been claimed, and what was found. */
    private static final class Run {
        private final TextNormalizer.Normalized normalized;
        private final String text;
        private final ZonedDateTime now;
        private final boolean[] claimed;
        private final Map<ConstraintField, List<Found>> found = new EnumMap<>(ConstraintField.class);
        private final List<Found> ambiguous = new ArrayList<>();

        /** A value or an ambiguity, with its span in the normalised text. */
        private record Found(ConstraintField field, Object value, AmbiguityReason reason, String message,
                             int start, int end) {
        }

        Run(TextNormalizer.Normalized normalized, ZonedDateTime now) {
            this.normalized = normalized;
            this.text = normalized.text();
            this.now = now;
            this.claimed = new boolean[text.length()];
        }

        ParsedConstraints parse() {
            asap();
            priority();
            exempt();
            flexibility();
            ranges();
            for (Pattern anchor : START_ANCHORS) {
                times(anchor, ConstraintField.ORIGINAL_START_TIME);
            }
            for (Pattern anchor : DEADLINE_ANCHORS) {
                times(anchor, ConstraintField.DEADLINE);
            }
            return result();
        }

        // ------------------------------------------------------------------ rules

        private void asap() {
            Matcher m = ASAP.matcher(text);
            while (m.find()) {
                if (free(m.start(), m.end())) {
                    ambiguity(null, AmbiguityReason.ASAP, "Does \"" + m.group() + "\" mean high priority, or start now?",
                            m.start(), m.end());
                }
            }
        }

        private void priority() {
            each(PRIORITY_LOW_PHRASE, m -> value(ConstraintField.PRIORITY, JobPriority.LOW, m.start(), m.end()));
            each(PRIORITY_NAMED, m -> value(ConstraintField.PRIORITY, priorityNamed(m.group(1)), m.start(), m.end()));
            each(PRIORITY_BEFORE_WORD, m -> negatableValue(ConstraintField.PRIORITY, priorityNamed(m.group(1)), m));
            each(PRIORITY_WORD, m -> negatableValue(ConstraintField.PRIORITY,
                    m.group(1).equals("critical") ? JobPriority.CRITICAL : JobPriority.HIGH, m));
        }

        private void exempt() {
            each(EXEMPT_NO, m -> value(ConstraintField.IS_EXEMPT, Boolean.FALSE, m.start(), m.end()));
            each(EXEMPT_YES, m -> negatableValue(ConstraintField.IS_EXEMPT, Boolean.TRUE, m));
        }

        private void flexibility() {
            each(FLEX_ZERO, m -> value(ConstraintField.FLEXIBILITY_WINDOW_HOURS, 0, m.start(), m.end()));
            for (Pattern pattern : FLEX_AMOUNT) {
                each(pattern, m -> {
                    Amounts.Amount amount = Amounts.read(m.group(1), m.group(2));
                    if (amount.problem() != null) {
                        ambiguity(ConstraintField.FLEXIBILITY_WINDOW_HOURS, amount.problem(),
                                amount.problem() == AmbiguityReason.VAGUE_AMOUNT
                                        ? "How many hours of flexibility exactly?"
                                        : "\"" + m.group() + "\" is too long to be meant. How many hours?",
                                m.start(), m.end());
                        return;
                    }
                    BigDecimal[] hours = amount.minutes().divideAndRemainder(BigDecimal.valueOf(60));
                    if (hours[1].signum() != 0) {
                        ambiguity(ConstraintField.FLEXIBILITY_WINDOW_HOURS, AmbiguityReason.NOT_WHOLE_HOURS,
                                "Flexibility is in whole hours. Round \"" + m.group() + "\" to how many hours?",
                                m.start(), m.end());
                        return;
                    }
                    value(ConstraintField.FLEXIBILITY_WINDOW_HOURS, hours[0].intValueExact(), m.start(), m.end());
                });
            }
            each(FLEX_NO_AMOUNT, m -> {
                if (!negated(m.start())) {
                    ambiguity(ConstraintField.FLEXIBILITY_WINDOW_HOURS, AmbiguityReason.FLEXIBILITY_AMOUNT_MISSING,
                            "How many hours can the job wait?", m.start(), m.end());
                }
            });
        }

        /** "between 10pm and 6am", "from Friday 8am to Saturday 6am". */
        private void ranges() {
            Matcher m = RANGE_START.matcher(text);
            while (m.find()) {
                if (!free(m.start(), m.end())) {
                    continue;
                }
                int limit = clauseEnd(m.end());
                DateTimeReader.Reading first = DateTimeReader.read(text, m.end(), limit, now, now.toLocalDateTime());
                if (first == null) {
                    continue;
                }
                Matcher join = RANGE_JOIN.matcher(text).region(first.end(), limit);
                if (!join.lookingAt()) {
                    continue;
                }
                LocalDateTime after = first.utc() == null ? now.toLocalDateTime() : local(first.utc());
                DateTimeReader.Reading second = DateTimeReader.read(text, join.end(), limit, now, after);
                if (second == null) {
                    continue;
                }
                record(ConstraintField.ORIGINAL_START_TIME, first, m.start(), first.end());
                record(ConstraintField.DEADLINE, second, join.start(), second.end());
            }
        }

        private void times(Pattern anchor, ConstraintField field) {
            Matcher m = anchor.matcher(text);
            while (m.find()) {
                if (!free(m.start(), m.end())) {
                    continue;
                }
                DateTimeReader.Reading reading = DateTimeReader.read(text, m.end(), clauseEnd(m.end()), now,
                        field == ConstraintField.DEADLINE ? deadlineBase() : now.toLocalDateTime());
                if (reading != null) {
                    record(field, reading, m.start(), reading.end());
                }
            }
        }

        /** A date-less deadline ("by 6am") is the first such time after the start, when the text gives one. */
        private LocalDateTime deadlineBase() {
            List<Found> starts = found.getOrDefault(ConstraintField.ORIGINAL_START_TIME, List.of());
            Set<Object> distinct = new LinkedHashSet<>();
            starts.forEach(f -> distinct.add(f.value()));
            if (distinct.size() == 1) {
                LocalDateTime start = local((LocalDateTime) distinct.iterator().next());
                if (start.isAfter(now.toLocalDateTime())) {
                    return start;
                }
            }
            return now.toLocalDateTime();
        }

        // ------------------------------------------------------------------ helpers

        private interface MatchAction {
            void accept(Matcher m);
        }

        private void each(Pattern pattern, MatchAction action) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                if (free(m.start(), m.end())) {
                    action.accept(m);
                }
            }
        }

        /** A positive phrase ("critical", "exempt") that a nearby "not" would turn into an ambiguity. */
        private void negatableValue(ConstraintField field, Object value, Matcher m) {
            if (negated(m.start())) {
                String before = phraseBefore(m.start());
                ambiguity(field, AmbiguityReason.NEGATED, "\"" + before + m.group()
                        + "\" says what this job is not. What should " + field.apiName() + " be?",
                        m.start() - before.length(), m.end());
            } else {
                value(field, value, m.start(), m.end());
            }
        }

        private void record(ConstraintField field, DateTimeReader.Reading reading, int start, int end) {
            if (reading.problem() != null) {
                ambiguity(field, reading.problem(), reading.message(), start, end);
            } else {
                value(field, reading.utc(), start, end);
            }
        }

        private void value(ConstraintField field, Object value, int start, int end) {
            found.computeIfAbsent(field, f -> new ArrayList<>()).add(new Found(field, value, null, null, start, end));
            claim(start, end);
        }

        private void ambiguity(ConstraintField field, AmbiguityReason reason, String message, int start, int end) {
            ambiguous.add(new Found(field, null, reason, message, start, end));
            claim(start, end);
        }

        private void claim(int start, int end) {
            for (int i = start; i < end; i++) {
                claimed[i] = true;
            }
        }

        private boolean free(int start, int end) {
            for (int i = start; i < end; i++) {
                if (claimed[i]) {
                    return false;
                }
            }
            return true;
        }

        /** A negating word among the three words before {@code position}. */
        private boolean negated(int position) {
            String[] words = phraseBefore(position).trim().split(" ");
            for (String word : words) {
                if (NEGATORS.contains(word)) {
                    return true;
                }
            }
            return false;
        }

        private String phraseBefore(int position) {
            int from = position;
            for (int words = 0; words < 3 && from > 0; words++) {
                from = text.lastIndexOf(' ', from - 2) + 1;
                if (from <= 0) {
                    from = 0;
                    break;
                }
            }
            String before = text.substring(from, position);
            int stop = Math.max(Math.max(before.lastIndexOf(','), before.lastIndexOf(';')), before.lastIndexOf('.'));
            return before.substring(stop + 1).stripLeading();
        }

        private int clauseEnd(int from) {
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ',' || c == ';' || c == '!' || c == '?' || c == '\n') {
                    return i;
                }
            }
            return text.length();
        }

        private LocalDateTime local(LocalDateTime utc) {
            return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(now.getZone()).toLocalDateTime();
        }

        private static JobPriority priorityNamed(String word) {
            return switch (word) {
                case "critical", "top", "highest" -> JobPriority.CRITICAL;
                case "high" -> JobPriority.HIGH;
                case "low", "lowest" -> JobPriority.LOW;
                default -> JobPriority.NORMAL;
            };
        }

        // ------------------------------------------------------------------ result

        private ParsedConstraints result() {
            Map<ConstraintField, Object> values = new EnumMap<>(ConstraintField.class);
            List<Evidence> evidence = new ArrayList<>();
            List<Conflict> conflicts = new ArrayList<>();
            Set<ConstraintField> unclear = new LinkedHashSet<>();
            ambiguous.forEach(a -> {
                if (a.field() != null) {
                    unclear.add(a.field());
                }
            });

            for (ConstraintField field : ConstraintField.values()) {
                List<Found> list = found.getOrDefault(field, List.of());
                Set<Object> distinct = new LinkedHashSet<>();
                list.forEach(f -> distinct.add(f.value()));
                if (distinct.size() > 1) {
                    List<String> phrases = list.stream().map(f -> normalized.originalSlice(f.start(), f.end())).toList();
                    conflicts.add(new Conflict(field, phrases, "The text gives " + field.apiName()
                            + " more than one value: " + String.join(" / ", phrases) + ". Which one is right?"));
                } else if (distinct.size() == 1 && !unclear.contains(field)) {
                    Object value = distinct.iterator().next();
                    values.put(field, value);
                    for (Found f : list) {
                        evidence.add(new Evidence(field, apiValue(value), normalized.originalSlice(f.start(), f.end()),
                                normalized.originalStartOf(f.start()), normalized.originalEndOf(f.end())));
                    }
                }
            }

            List<Ambiguity> ambiguities = ambiguous.stream()
                    .map(a -> new Ambiguity(a.field(), a.reason(), normalized.originalSlice(a.start(), a.end()),
                            a.message(), normalized.originalStartOf(a.start()), normalized.originalEndOf(a.end())))
                    .toList();

            return new ParsedConstraints(
                    (LocalDateTime) values.get(ConstraintField.ORIGINAL_START_TIME),
                    (LocalDateTime) values.get(ConstraintField.DEADLINE),
                    (Integer) values.get(ConstraintField.FLEXIBILITY_WINDOW_HOURS),
                    (Boolean) values.get(ConstraintField.IS_EXEMPT),
                    (JobPriority) values.get(ConstraintField.PRIORITY),
                    evidence, ambiguities, conflicts, unsupported());
        }

        /** Clauses that no rule claimed any part of. */
        private List<Unsupported> unsupported() {
            List<Unsupported> out = new ArrayList<>();
            Matcher breaks = CLAUSE_BREAK.matcher(text);
            int from = 0;
            while (from <= text.length()) {
                int to = breaks.find(from) ? breaks.start() : text.length();
                int next = to == text.length() ? text.length() + 1 : breaks.end();
                int s = from;
                int e = to;
                while (s < e && !Character.isLetterOrDigit(text.charAt(s))) {
                    s++;
                }
                while (e > s && !Character.isLetterOrDigit(text.charAt(e - 1))) {
                    e--;
                }
                if (s < e && free(s, e) && !FILLER_ONLY.matcher(text.substring(s, e)).matches()) {
                    out.add(new Unsupported(normalized.originalSlice(s, e), normalized.originalStartOf(s),
                            normalized.originalEndOf(e)));
                }
                from = Math.max(next, from + 1);
            }
            return out;
        }

        private static String apiValue(Object value) {
            if (value instanceof LocalDateTime time) {
                return time.format(API_TIME);
            }
            return String.valueOf(value);
        }
    }
}
