package com.gacs.backend.dto;

import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.ReviewState;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.UUID;

public record ScheduleDecisionDto(Long decisionId, UUID runId, AuditActionType action, Long operatorId,
                                 String comment, Instant decidedAt, ReviewState reviewState) {
    public record Request(@Schema(description = "Required nonblank rationale, trimmed after validating its original length", maxLength = 2000)
                          @NotBlank @Size(max = 2000)
                          @JsonDeserialize(using = CommentDeserializer.class) String comment) {}

    /** Keep scalar coercion disabled for this request without changing other API payloads. */
    public static class CommentDeserializer extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getString();
        }
    }
}
