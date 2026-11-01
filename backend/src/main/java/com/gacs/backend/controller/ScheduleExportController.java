package com.gacs.backend.controller;

import com.gacs.backend.service.ScheduleExportService;
import com.gacs.backend.service.ScheduleExportService.ExportFile;
import com.gacs.backend.service.ScheduleExportService.Format;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/**
 * Approved-schedule downloads for execution adapters (docs/api-contract.md section 6.6, GRID-135 AC3).
 * Only the plan's owner can export it, and only while its approval stands.
 */
@RestController
@RequestMapping("/api/schedules/{runId}")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Schedule Export", description = "Downloads an approved schedule as CSV or JSON. Unapproved plans are "
    + "refused with 403; stale or invalidated approvals with 409. Missing and unowned plans return 404.")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The schedule file, "
        + "sent as an attachment named schedule-run-<runId>.csv or .json."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Malformed run ID."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authenticated email has no database user."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing or invalid JWT "
        + "(default error body, no data.reason), or the plan is not approved (error envelope with data.reason "
        + "NOT_APPROVED)."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Optimization plan not found or not owned by the operator."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The approval is stale "
        + "(data.reason STALE) or invalidated (data.reason INVALIDATED); the message says why.")
})
public class ScheduleExportController {

    private final ScheduleExportService exports;

    public ScheduleExportController(ScheduleExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/export.csv")
    @Operation(summary = "Download an approved schedule as CSV", description = "Format: contract section 6.6.")
    public ResponseEntity<byte[]> exportCsv(
            @Parameter(description = "Optimiser run ID", required = true) @PathVariable UUID runId,
            Principal principal
    ) {
        return attachment(exports.export(runId, Format.CSV, principal.getName()));
    }

    @GetMapping("/export.json")
    @Operation(summary = "Download an approved schedule as JSON", description = "Format: contract section 6.6.")
    public ResponseEntity<byte[]> exportJson(
            @Parameter(description = "Optimiser run ID", required = true) @PathVariable UUID runId,
            Principal principal
    ) {
        return attachment(exports.export(runId, Format.JSON, principal.getName()));
    }

    private static ResponseEntity<byte[]> attachment(ExportFile file) {
        return ResponseEntity.ok()
                .contentType(file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }
}
