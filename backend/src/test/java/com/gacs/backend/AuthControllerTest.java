package com.gacs.backend;

import com.gacs.backend.controller.AuthController;
import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.AuthResponse;
import com.gacs.backend.dto.LoginRequest;
import com.gacs.backend.dto.RegisterRequest;
import com.gacs.backend.dto.UserDto;
import com.gacs.backend.dto.VerifyOtpRequest;
import com.gacs.backend.model.User;
import com.gacs.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(com.gacs.backend.config.SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void register_ShouldReturn200_WhenRequestValid() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(AuthResponse.otpRequired("OTP sent", "elena@datacenter.io"));

        String payload = """
                {
                    "email": "elena@datacenter.io",
                    "password": "Password123!",
                    "fullName": "Elena Vance",
                    "role": "Data Center Operations Manager"
                }
                """;

        mockMvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.requiresOtp").value(true))
                .andExpect(jsonPath("$.email").value("elena@datacenter.io"));
    }

    @Test
    void register_ShouldReturn400_WhenEmailInvalid() throws Exception {
        String payload = """
                {
                    "email": "not-an-email",
                    "password": "Password123!",
                    "fullName": "Elena Vance",
                    "role": "Data Center Operations Manager"
                }
                """;

        mockMvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyOtp_ShouldReturn200_WhenValid() throws Exception {
        User user = new User("elena@datacenter.io", "encoded", "Elena Vance", "Operations Manager");
        user.setId(1L);
        user.setVerified(true);
        when(authService.verifyOtp(any(VerifyOtpRequest.class)))
                .thenReturn(AuthResponse.success("Verified successfully", new UserDto(user)));

        String payload = """
                {
                    "email": "elena@datacenter.io",
                    "otp": "654321"
                }
                """;

        mockMvc.perform(post("/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.user.email").value("elena@datacenter.io"));
    }

    @Test
    void login_ShouldReturn200_WhenValid() throws Exception {
        User user = new User("elena@datacenter.io", "encoded", "Elena Vance", "Operations Manager");
        user.setId(1L);
        user.setVerified(true);
        when(authService.login(any(LoginRequest.class), any()))
                .thenReturn(AuthResponse.success("Login successful", new UserDto(user)));

        String payload = """
                {
                    "email": "elena@datacenter.io",
                    "password": "Password123!"
                }
                """;

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.user.email").value("elena@datacenter.io"));
    }

    @Test
    void logout_ShouldReturn200() throws Exception {
        when(authService.logout(any())).thenReturn(ApiResponse.ok("Logged out successfully."));

        mockMvc.perform(post("/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Logged out successfully."));
    }
}
