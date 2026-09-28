package com.gacs.backend;

import com.gacs.backend.config.SecurityConfig;
import com.gacs.backend.controller.AdminController;
import com.gacs.backend.dto.UserDto;
import com.gacs.backend.model.User;
import com.gacs.backend.service.AdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminService adminService;

    @Test
    @WithMockUser(username = "admin@datacenter.io", roles = {"ADMIN"})
    void getAllUsers_ShouldReturn200_WhenUserIsAdmin() throws Exception {
        User user1 = new User("admin@datacenter.io", "pass", "Admin", "ROLE_ADMIN");
        user1.setId(1L);
        user1.setVerified(true);

        User user2 = new User("operator@datacenter.io", "pass", "Operator", "ROLE_USER");
        user2.setId(2L);
        user2.setVerified(true);

        when(adminService.getAllUsers()).thenReturn(List.of(new UserDto(user1), new UserDto(user2)));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].email").value("admin@datacenter.io"))
                .andExpect(jsonPath("$.data[1].email").value("operator@datacenter.io"));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io", roles = {"USER"})
    void getAllUsers_ShouldReturn403_WhenUserIsStandardUser() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAllUsers_ShouldReturn403_WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@datacenter.io", roles = {"ADMIN"})
    void updateUserRole_ShouldReturn200_WhenPromotingUser() throws Exception {
        User updatedUser = new User("operator@datacenter.io", "pass", "Operator", "ROLE_ADMIN");
        updatedUser.setId(2L);
        updatedUser.setVerified(true);

        when(adminService.updateUserRole(eq(2L), eq("ROLE_ADMIN"), eq("admin@datacenter.io")))
                .thenReturn(new UserDto(updatedUser));

        String payload = """
                {
                    "role": "ROLE_ADMIN"
                }
                """;

        mockMvc.perform(patch("/api/admin/users/2/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("ROLE_ADMIN"));
    }

    @Test
    @WithMockUser(username = "admin@datacenter.io", roles = {"ADMIN"})
    void updateUserRole_ShouldReturn400_WhenAdminAttemptsSelfDemotion() throws Exception {
        when(adminService.updateUserRole(eq(1L), eq("ROLE_USER"), eq("admin@datacenter.io")))
                .thenThrow(new IllegalArgumentException("You cannot demote your own administrator account."));

        String payload = """
                {
                    "role": "ROLE_USER"
                }
                """;

        mockMvc.perform(patch("/api/admin/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("You cannot demote your own administrator account."));
    }

    @Test
    @WithMockUser(username = "operator@datacenter.io", roles = {"USER"})
    void updateUserRole_ShouldReturn403_WhenStandardUserAttemptsRoleChange() throws Exception {
        String payload = """
                {
                    "role": "ROLE_ADMIN"
                }
                """;

        mockMvc.perform(patch("/api/admin/users/2/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
    }
}
