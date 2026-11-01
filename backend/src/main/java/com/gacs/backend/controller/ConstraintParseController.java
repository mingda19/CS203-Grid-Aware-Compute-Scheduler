package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.ConstraintParseRequestDto;
import com.gacs.backend.dto.ConstraintParseResponseDto;
import com.gacs.backend.service.constraintparse.ConstraintParseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Natural-language constraint parsing for the job form (GRID-39; docs/constraint-parser-plan.md). */
@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Job Constraint Parsing", description = "Turn plain-English job constraints into job fields for confirmation")
public class ConstraintParseController {

    private final ConstraintParseService service;

    public ConstraintParseController(ConstraintParseService service) {
        this.service = service;
    }

    @Operation(
            summary = "Parse plain-English job constraints",
            description = "Reads start time, deadline, flexibility, exemption and priority from the text, applies them "
                    + "to the form's current values and checks the result with the job rules. Ambiguous phrases are "
                    + "left unset and returned as questions. Nothing is saved."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Parsed. Check status: PARSED, NEEDS_CLARIFICATION or INVALID.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Blank text, text over 1,000 characters, or an unknown time zone."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT access token.")
    })
    @PostMapping("/parse-constraints")
    public ResponseEntity<ApiResponse<ConstraintParseResponseDto>> parse(
            @Valid @RequestBody ConstraintParseRequestDto request) {
        return ResponseEntity.ok(ApiResponse.ok("Constraints parsed", service.parse(request)));
    }
}
