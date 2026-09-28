package com.gacs.backend;

import com.gacs.backend.controller.AuthController;
import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.AuthResponse;
import com.gacs.backend.dto.LoginRequest;
import com.gacs.backend.dto.RegisterRequest;
import com.gacs.backend.dto.UserDto;
import com.gacs.backend.dto.VerifyOtpRequest;
import com.gacs.backend.model.User;
import com.gacs.backend.security.JwtAuthenticationFilter;
import com.gacs.backend.security.JwtUtils;
import com.gacs.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                    "fullName": "Elena Vance"
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
                    "fullName": "Elena Vance"
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
    void login_ShouldReturn200AndJwtToken_WhenValid() throws Exception {
        User user = new User("elena@datacenter.io", "encoded", "Elena Vance", "ROLE_USER");
        user.setId(1L);
        user.setVerified(true);
        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenReturn(AuthResponse.successWithToken("Login successful", new UserDto(user), "mock.jwt.token", 900L, 1209600L));

        String payload = """
                {
                    "email": "elena@datacenter.io",
                    "password": "Password123!",
                    "rememberMe": true
                }
                """;

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.user.email").value("elena@datacenter.io"))
                .andExpect(jsonPath("$.accessToken").value("mock.jwt.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshExpiresIn").value(1209600));
    }

    @Test
    void refreshToken_ShouldReturn200AndNewAccessToken() throws Exception {
        User user = new User("elena@datacenter.io", "encoded", "Elena Vance", "ROLE_USER");
        user.setId(1L);
        user.setVerified(true);
        when(authService.refreshToken(any(), any()))
                .thenReturn(AuthResponse.successWithToken("Token refreshed successfully.", new UserDto(user), "new.mock.jwt.token", 900L, 1209600L));

        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.accessToken").value("new.mock.jwt.token"))
                .andExpect(jsonPath("$.refreshExpiresIn").value(1209600));
    }

    @Test
    @WithMockUser(username = "elena@datacenter.io", roles = {"USER"})
    void getCurrentUser_ShouldReturn200_WhenAuthenticated() throws Exception {
        User user = new User("elena@datacenter.io", "encoded", "Elena Vance", "ROLE_USER");
        user.setId(1L);
        when(authService.getCurrentUser("elena@datacenter.io")).thenReturn(new UserDto(user));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("elena@datacenter.io"));
    }

    @Test
    void logout_ShouldReturn200() throws Exception {
        when(authService.logout(any(), any())).thenReturn(ApiResponse.ok("Logged out successfully."));

        mockMvc.perform(post("/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Logged out successfully."));
    }
}
