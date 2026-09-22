package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.UpdateRoleRequest;
import com.gacs.backend.dto.UserDto;
import com.gacs.backend.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserDto>>> getAllUsers() {
        List<UserDto> users = adminService.getAllUsers();
        return ResponseEntity.ok(ApiResponse.ok("Users retrieved successfully", users));
    }

    /**
     * Updates a user's role (ROLE_USER or ROLE_ADMIN). Only accessible by ADMIN.
     */
    @PatchMapping("/{id}/role")
    public ResponseEntity<ApiResponse<UserDto>> updateUserRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request,
            Authentication authentication
    ) {
        String adminEmail = authentication != null ? authentication.getName() : "";
        UserDto updatedUser = adminService.updateUserRole(id, request.getRole(), adminEmail);
        return ResponseEntity.ok(ApiResponse.ok("User role updated successfully", updatedUser));
    }
}
