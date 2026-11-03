package com.gacs.backend.dto;

import com.gacs.backend.model.ConfirmationOutcome;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Schema(description = "Request payload for creating or updating a compute job")
@Getter
@Setter
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
@Builder
public class JobRequestDto {

    @Schema(description = "Human-readable workload name", example = "LLM Fine-Tuning Run #42", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Job name is required")
    @Size(max = 255, message = "Job name must not exceed 255 characters")
    private String name;

    @Schema(description = "Total CPU core-hours required", example = "64.0", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "cpuHours is required")
    @PositiveOrZero(message = "cpuHours must be non-negative")
    @Builder.Default
    private Double cpuHours = 0.0;

    @Schema(description = "Total GPU hours required", example = "16.0", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "gpuHours is required")
    @PositiveOrZero(message = "gpuHours must be non-negative")
    @Builder.Default
    private Double gpuHours = 0.0;

    @Schema(description = "Baseline scheduled start time in UTC ISO-8601", example = "2026-10-20T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "originalStartTime is required")
    private LocalDateTime originalStartTime;

    @Schema(description = "Hard completion deadline in UTC ISO-8601", example = "2026-10-21T18:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "deadline is required")
    private LocalDateTime deadline;

    @Schema(description = "Flexibility delay tolerance in hours", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "flexibilityWindowHours is required")
    @PositiveOrZero(message = "flexibilityWindowHours must be non-negative")
    @Builder.Default
    private Integer flexibilityWindowHours = 0;

    @Schema(description = "Flag indicating exemption from automatic rescheduling", example = "false")
    @JsonProperty("isExempt")
    @JsonAlias({"exempt", "is_exempt"})
    @Builder.Default
    private Boolean isExempt = false;

    @Schema(description = "Original natural-language source retained verbatim; omitted on update preserves it, null clears it", example = "Can wait up to 12 hours for low price valley, finish by tomorrow evening")
    @Size(max = 1000, message = "rawConstraints must not exceed 1000 characters")
    private String rawConstraints;

    @Schema(description = "Actual caller-supplied parser timestamp in UTC ISO-8601; omitted on update preserves it, null clears it", example = "2026-10-20T09:30:00")
    private LocalDateTime parsedAt;

    @Schema(description = "Latest parse decision; omitted on update preserves it unless parsedAt is supplied (defaults to PENDING); explicit null clears it", allowableValues = {"PENDING", "CONFIRMED", "REJECTED"})
    private ConfirmationOutcome confirmationOutcome;

    @Schema(description = "Operational status", example = "PENDING", allowableValues = {"PENDING", "SCHEDULED", "RUNNING", "COMPLETED", "CANCELLED"})
    @Size(max = 30, message = "status must not exceed 30 characters")
    @Builder.Default
    private String status = "PENDING";

    @Schema(description = "Workload priority level", example = "NORMAL", allowableValues = {"CRITICAL", "HIGH", "NORMAL", "LOW"})
    @Builder.Default
    private String priority = "NORMAL";

    // Presence distinguishes an omitted update field from an explicit JSON null.
    @JsonIgnore
    @Schema(hidden = true)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean rawConstraintsPresent;

    public void setRawConstraints(String value) {
        this.rawConstraints = value;
        this.rawConstraintsPresent = true;
    }

    @JsonIgnore
    public boolean hasRawConstraints() {
        return rawConstraintsPresent || rawConstraints != null;
    }

    @JsonIgnore
    @Schema(hidden = true)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean parsedAtPresent;

    public void setParsedAt(LocalDateTime value) {
        this.parsedAt = value;
        this.parsedAtPresent = true;
    }

    @JsonIgnore
    public boolean hasParsedAt() {
        return parsedAtPresent || parsedAt != null;
    }

    @JsonIgnore
    @Schema(hidden = true)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean confirmationOutcomePresent;

    public void setConfirmationOutcome(ConfirmationOutcome value) {
        this.confirmationOutcome = value;
        this.confirmationOutcomePresent = true;
    }

    @JsonIgnore
    public boolean hasConfirmationOutcome() {
        return confirmationOutcomePresent || confirmationOutcome != null;
    }

    public static class JobRequestDtoBuilder {
        public JobRequestDtoBuilder rawConstraints(String value) {
            this.rawConstraints = value;
            this.rawConstraintsPresent = true;
            return this;
        }

        public JobRequestDtoBuilder parsedAt(LocalDateTime value) {
            this.parsedAt = value;
            this.parsedAtPresent = true;
            return this;
        }

        public JobRequestDtoBuilder confirmationOutcome(ConfirmationOutcome value) {
            this.confirmationOutcome = value;
            this.confirmationOutcomePresent = true;
            return this;
        }

    }

    @JsonProperty("isExempt")
    public boolean isExempt() {
        return Boolean.TRUE.equals(isExempt);
    }

    @JsonProperty("isExempt")
    public void setExempt(Boolean exempt) {
        this.isExempt = (exempt != null) ? exempt : false;
    }
}
