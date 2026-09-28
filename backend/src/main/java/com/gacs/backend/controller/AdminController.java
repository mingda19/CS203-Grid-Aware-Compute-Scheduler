package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.UpdateRoleRequest;
import com.gacs.backend.dto.UserDto;
import com.gacs.backend.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin User Management", description = "Administrative endpoints for managing users and role assignments. Requires active session with ROLE_ADMIN privileges.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Lists all registered users. Only accessible by ADMIN.
     */
    @Operation(
            summary = "List all registered users",
            description = "Retrieves a list of all user accounts in the platform, including their IDs, email addresses, "
                    + "full names, assigned roles, and verification statuses. Requires ROLE_ADMIN privilege."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "List of users retrieved successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT token."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden: User lacks ROLE_ADMIN privilege."
            )
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserDto>>> getAllUsers() {
        List<UserDto> users = adminService.getAllUsers();
        return ResponseEntity.ok(ApiResponse.ok("Users retrieved successfully", users));
    }

    /**
     * Updates a user's role (ROLE_USER or ROLE_ADMIN). Only accessible by ADMIN.
     */
    @Operation(
            summary = "Update a user's authorization role",
            description = "Modifies the role assigned to a specific user. Available roles: ROLE_USER, ROLE_ADMIN. "
                    + "An administrator cannot demote their own account. Requires ROLE_ADMIN privilege."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "User role updated successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Invalid role value, target user not found, or attempted self-demotion."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT token."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden: User lacks ROLE_ADMIN privilege."
            )
    })
    @PatchMapping("/{id}/role")
    public ResponseEntity<ApiResponse<UserDto>> updateUserRole(
            @Parameter(description = "Unique numeric identifier of the user to update", required = true, example = "2")
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request,
            Authentication authentication
    ) {
        String adminEmail = authentication != null ? authentication.getName() : "";
        UserDto updatedUser = adminService.updateUserRole(id, request.getRole(), adminEmail);
        return ResponseEntity.ok(ApiResponse.ok("User role updated successfully", updatedUser));
    }
}
