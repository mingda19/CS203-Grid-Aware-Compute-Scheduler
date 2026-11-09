package com.gacs.backend.service.constraintparse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * The parts of a System One response this code reads, as the API actually returns them.
 *
 * <p>Shapes confirmed against live responses rather than taken from the schema alone:
 * <pre>
 *   noul:   {"type":"noul","noul":0.99}
 *   choice: {"type":"choice","choice":"102","confidence":0.94,
 *            "probabilities":{"101":0.04,"102":0.96,"103":0.0}}
 * </pre>
 *
 * <p>Note what a Noul does <em>not</em> carry: there is no confidence field on it at all. And on a
 * Choice, {@code confidence} and the chosen option's probability are different numbers - 0.94
 * against 0.96 in the response above - because confidence describes how concentrated the
 * distribution is, not how likely the pick is to be right. Code reads
 * {@code probabilities.get(choice)}.
 *
 * <p>Unknown fields are ignored so a future response gaining one does not break parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JevAnswers(String model, Map<String, Answer> answers, Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Answer(String type, Double noul, String choice, Double confidence,
                         Map<String, Double> probabilities) {

        /** A Noul's probability, or 0 when this answer is missing or not a Noul. */
        public double nounProbability() {
            return noul == null ? 0.0 : noul;
        }

        /** The probability of the option actually chosen - never {@code confidence}. */
        public double chosenProbability() {
            if (choice == null || probabilities == null) {
                return 0.0;
            }
            return probabilities.getOrDefault(choice, 0.0);
        }
    }

    /**
     * Token counts, for cost accounting. The field names are mapped explicitly because the API
     * sends {@code input_tokens} and Jackson is not configured to translate snake_case here -
     * without this the counts bind to null silently and every logged cost reads as zero.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens) { }

    /** The answer to {@code id}, or an empty one, so a missing question reads as "no" not a crash. */
    public Answer answer(String id) {
        Answer found = answers == null ? null : answers.get(id);
        return found == null ? new Answer(null, null, null, null, null) : found;
    }
}
