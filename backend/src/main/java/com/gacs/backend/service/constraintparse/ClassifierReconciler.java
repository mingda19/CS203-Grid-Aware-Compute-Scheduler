package com.gacs.backend.service.constraintparse;

import com.gacs.backend.service.allocation.ConstraintType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Puts the classifier's reading of the text beside the rule-based parser's and reports where they
 * disagree.
 *
 * <p>The two read the same text for different things. {@link ConstraintParser} extracts
 * <em>values</em> - a deadline of 18:00 on Friday - and is exact about time zones, DST and relative
 * phrases. The classifier only says which <em>kinds</em> of constraint the text appears to state,
 * with a probability. Neither is authoritative over the other, so where they differ the operator
 * decides rather than this code picking a winner.
 *
 * <p>Only two types can disagree at all: {@link ConstraintType#DEADLINE} and
 * {@link ConstraintType#FLEXIBILITY_WINDOW} are the only ones the classifier asks about that the
 * parser also produces a field for. The parser has no field for a recurring daily window and no
 * notion of pair constraints, and the classifier is asked nothing about start time, exemption or
 * priority - so for everything else there is only one source and nothing to reconcile. Those
 * classifier-only findings come back as suggestions instead, which the operator confirms on the
 * guardrail's terms.
 *
 * <p>Pure, and separate from the HTTP call and the service, so every rule below is testable without
 * a network, a key or a Spring context.
 */
public final class ClassifierReconciler {

    /** The classifier questions that have a parser field to disagree with. */
    private static final Map<ConstraintType, ConstraintField> COMPARABLE = new EnumMap<>(Map.of(
            ConstraintType.DEADLINE, ConstraintField.DEADLINE,
            ConstraintType.FLEXIBILITY_WINDOW, ConstraintField.FLEXIBILITY_WINDOW_HOURS));

    private ClassifierReconciler() {
    }

    /**
     * One disagreement, with the choice the operator has to make.
     *
     * @param field       the job field in question
     * @param parsedValue what the rule-based parser read, or null when it read nothing
     * @param probability how likely the classifier thinks this kind of constraint is stated, 0-1,
     *                    or null when the classifier did not report this type at all. Null is not
     *                    zero: the classifier's score for a type it scored below the floor is not
     *                    carried this far, and inventing a number for it would read as a
     *                    measurement. The operator's choice does not depend on it either way.
     * @param question    what to put to the operator
     * @param options     the choices, in the order to present them; ids are stable for the frontend
     */
    public record Disagreement(ConstraintField field, String parsedValue, Double probability,
                               String question, List<Option> options) {
        public Disagreement {
            options = List.copyOf(options);
        }
    }

    /** One selectable answer. {@code id} is matched in code; {@code label} is read by a person. */
    public record Option(String id, String label) { }

    /** A constraint only the classifier found, for the operator to confirm or discard. */
    public record Suggestion(ConstraintType type, double probability, String phrase,
                             Long referencedJobId, boolean needsConfirmation) { }

    public record Reconciliation(List<Suggestion> suggestions, List<Disagreement> disagreements) {
        public Reconciliation {
            suggestions = List.copyOf(suggestions);
            disagreements = List.copyOf(disagreements);
        }

        public static final Reconciliation NONE = new Reconciliation(List.of(), List.of());
    }

    /**
     * @param parsed   what the rule-based parser read from the text
     * @param accepted the proposals that survived {@link ConstraintGuardrail}; a refused proposal is
     *                 not evidence of anything and takes no part here
     * @param offered  the types the classifier was actually asked about. Needed to tell "the
     *                 classifier does not think the text sets a deadline" from "nothing asked it",
     *                 which look identical in {@code accepted} and mean opposite things - the first
     *                 is a disagreement to settle, the second is no information at all.
     */
    public static Reconciliation reconcile(ParsedConstraints parsed,
                                           List<ConstraintGuardrail.Accepted> accepted,
                                           Set<ConstraintType> offered) {
        List<Suggestion> suggestions = new ArrayList<>();
        List<Disagreement> disagreements = new ArrayList<>();

        for (ConstraintGuardrail.Accepted entry : accepted) {
            ConstraintType type = entry.proposal().type();
            if (!COMPARABLE.containsKey(type)) {
                suggestions.add(new Suggestion(type, entry.proposal().probability(),
                        entry.proposal().phrase(), entry.referencedJobId(), entry.needsConfirmation()));
            }
        }

        for (Map.Entry<ConstraintType, ConstraintField> pair : COMPARABLE.entrySet()) {
            ConstraintType type = pair.getKey();
            if (!offered.contains(type)) {
                continue;
            }
            ConstraintField field = pair.getValue();
            String parsedValue = valueOf(parsed, field);
            Double probability = accepted.stream()
                    .filter(a -> a.proposal().type() == type)
                    .map(a -> a.proposal().probability())
                    .findFirst().orElse(null);
            boolean classifierSaysPresent = probability != null;

            if (classifierSaysPresent && parsedValue == null) {
                // The classifier sees this kind of constraint; the parser could not turn it into a
                // value. Usually a phrasing the parser's readers do not cover. The operator is the
                // only one who can supply the value, so the choice is to set it or say it is not there.
                disagreements.add(new Disagreement(field, null, probability,
                        "The text looks like it sets " + readable(field) + ", but no value could be "
                                + "read from it. Set it yourself, or leave it out?",
                        List.of(new Option("ENTER_VALUE", "I will set " + readable(field) + " in the form"),
                                new Option("IGNORE", "The text does not set " + readable(field)))));
            } else if (!classifierSaysPresent && parsedValue != null) {
                // The parser produced a value the classifier does not think the text asks for. The
                // parser may have matched a phrase that means something else, so the value is shown
                // rather than silently kept or silently dropped.
                disagreements.add(new Disagreement(field, parsedValue, probability,
                        "The text was read as setting " + readable(field) + " to " + parsedValue
                                + ", but it does not look like it states one. Keep it?",
                        List.of(new Option("KEEP", "Keep " + readable(field) + " as " + parsedValue),
                                new Option("DROP", "Remove it - the text does not set " + readable(field)))));
            }
        }
        return new Reconciliation(suggestions, disagreements);
    }

    private static String valueOf(ParsedConstraints parsed, ConstraintField field) {
        return switch (field) {
            case DEADLINE -> Optional.ofNullable(parsed.deadline()).map(String::valueOf).orElse(null);
            case FLEXIBILITY_WINDOW_HOURS -> Optional.ofNullable(parsed.flexibilityWindowHours())
                    .map(hours -> hours + " hours").orElse(null);
            default -> null;
        };
    }

    /** The field as an operator would say it, never the API spelling. */
    private static String readable(ConstraintField field) {
        return switch (field) {
            case DEADLINE -> "a deadline";
            case FLEXIBILITY_WINDOW_HOURS -> "a delay allowance";
            case ORIGINAL_START_TIME -> "a start time";
            case IS_EXEMPT -> "an exemption";
            case PRIORITY -> "a priority";
        };
    }
}
