package com.gacs.backend.service.allocation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The LLM's output schema, squashed to exactly what it needs to do
 * (docs/schedule-explanation-design.md section 3): narrate already-known facts, never compute or
 * restate a number. Every cost/shift/slack figure is backend-computed and supplied as prompt input;
 * the LLM only ever writes prose into {@code explanation} fields, keyed back to the backend's own
 * job/constraint identities.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmExplanationResponse(
        String headline,
        String narrative,
        List<JobExplanation> jobExplanations,
        List<ConstraintExplanation> constraintExplanations) {

    public LlmExplanationResponse {
        jobExplanations = jobExplanations == null ? List.of() : List.copyOf(jobExplanations);
        constraintExplanations = constraintExplanations == null ? List.of() : List.copyOf(constraintExplanations);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JobExplanation(long jobId, String explanation) {
    }

    /**
     * @param relatedJobIds disambiguates which explanation belongs to which when a job has two
     *                      constraints of the same type (e.g. two separate precedence
     *                      relationships) - matched back to a {@code ConstraintImpact} by
     *                      {@code (jobId, constraintType, relatedJobIds)}, never by array position
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConstraintExplanation(long jobId, String constraintType, List<Long> relatedJobIds,
                                        String explanation) {
        public ConstraintExplanation {
            relatedJobIds = relatedJobIds == null ? List.of() : List.copyOf(relatedJobIds);
        }
    }
}
