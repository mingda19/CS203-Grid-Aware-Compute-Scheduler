package com.gacs.backend;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.AuthResponse;
import com.gacs.backend.dto.LoginRequest;
import com.gacs.backend.dto.RegisterRequest;
import com.gacs.backend.dto.VerifyOtpRequest;
import com.gacs.backend.model.OtpVerification;
import com.gacs.backend.model.RefreshToken;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.OtpVerificationRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.security.JwtUtils;
import com.gacs.backend.service.AuthService;
import com.gacs.backend.service.EmailService;
import com.gacs.backend.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OtpVerificationRepository otpRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private AuthService authService;

    private RegisterRequest registerRequest;

    @BeforeEach
    void setUp() {
        registerRequest = new RegisterRequest(
                "elena.vance@datacenter.io",
                "P@ssw0rd2026!",
                "Elena Vance"
        );
    }

    @Test
    void register_ShouldEncryptPasswordAndSaveUnverifiedUser() {
        when(userRepository.findByEmail("elena.vance@datacenter.io")).thenReturn(Optional.empty());
        when(otpRepository.save(any(OtpVerification.class))).thenAnswer(i -> i.getArgument(0));

        AuthResponse response = authService.register(registerRequest);

        assertTrue(response.isSuccess());
        assertTrue(response.isRequiresOtp());

        // Verify user saved with ENCRYPTED password
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        assertNotNull(savedUser);
        assertNotEquals("P@ssw0rd2026!", savedUser.getPassword());
        assertTrue(passwordEncoder.matches("P@ssw0rd2026!", savedUser.getPassword()));
        assertEquals("ROLE_USER", savedUser.getRole());
        assertFalse(savedUser.isVerified());

        // Verify OTP was generated and saved
        verify(otpRepository).save(any(OtpVerification.class));
        verify(emailService).sendOtpEmail(eq("elena.vance@datacenter.io"), anyString());
    }

    @Test
    void register_ShouldThrowException_WhenUserAlreadyVerified() {
        User existingVerifiedUser = new User("elena.vance@datacenter.io", "hashedPass", "Elena", "Role");
        existingVerifiedUser.setVerified(true);
        when(userRepository.findByEmail("elena.vance@datacenter.io")).thenReturn(Optional.of(existingVerifiedUser));

        assertThrows(IllegalArgumentException.class, () -> authService.register(registerRequest));
    }

    @Test
    void verifyOtp_ShouldMarkUserVerified_WhenOtpIsValid() {
        OtpVerification otp = new OtpVerification("elena.vance@datacenter.io", "123456", LocalDateTime.now().plusMinutes(10));
        User unverifiedUser = new User("elena.vance@datacenter.io", "hashedPass", "Elena", "Role");
        unverifiedUser.setVerified(false);

        when(otpRepository.findTopByEmailAndOtpCodeAndIsUsedFalseOrderByCreatedAtDesc("elena.vance@datacenter.io", "123456"))
                .thenReturn(Optional.of(otp));
        when(userRepository.findByEmail("elena.vance@datacenter.io")).thenReturn(Optional.of(unverifiedUser));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        VerifyOtpRequest request = new VerifyOtpRequest("elena.vance@datacenter.io", "123456");
        AuthResponse response = authService.verifyOtp(request);

        assertTrue(response.isSuccess());
        assertTrue(otp.isUsed());
        assertTrue(unverifiedUser.isVerified());
        assertNotNull(response.getUser());
        assertTrue(response.getUser().isVerified());
    }

    @Test
    void verifyOtp_ShouldThrowException_WhenOtpExpired() {
        OtpVerification expiredOtp = new OtpVerification("elena.vance@datacenter.io", "123456", LocalDateTime.now().minusMinutes(1));

        when(otpRepository.findTopByEmailAndOtpCodeAndIsUsedFalseOrderByCreatedAtDesc("elena.vance@datacenter.io", "123456"))
                .thenReturn(Optional.of(expiredOtp));

        VerifyOtpRequest request = new VerifyOtpRequest("elena.vance@datacenter.io", "123456");
        assertThrows(IllegalArgumentException.class, () -> authService.verifyOtp(request));
    }

    @Test
    void login_ShouldSucceedWithJwtAndRefreshToken_WhenCredentialsCorrect() {
        String hashedPassword = passwordEncoder.encode("P@ssw0rd2026!");
        User verifiedUser = new User("elena.vance@datacenter.io", hashedPassword, "Elena Vance", "ROLE_USER");
        verifiedUser.setId(1L);
        verifiedUser.setVerified(true);

        when(userRepository.findByEmail("elena.vance@datacenter.io")).thenReturn(Optional.of(verifiedUser));
        when(jwtUtils.generateAccessToken(any(User.class))).thenReturn("mock.access.token");
        when(jwtUtils.getJwtExpirationMs()).thenReturn(900000L);

        RefreshToken refreshToken = new RefreshToken("sampleRefreshToken123", verifiedUser, Instant.now().plusSeconds(86400), false);
        when(refreshTokenService.createRefreshToken(any(User.class), eq(false))).thenReturn(refreshToken);
        when(refreshTokenService.getExpirationSeconds(anyBoolean())).thenReturn(86400L);

        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        LoginRequest loginRequest = new LoginRequest("elena.vance@datacenter.io", "P@ssw0rd2026!", false);

        AuthResponse response = authService.login(loginRequest, mockRequest, mockResponse);

        assertTrue(response.isSuccess());
        assertNotNull(response.getUser());
        assertEquals("elena.vance@datacenter.io", response.getUser().getEmail());
        assertEquals("mock.access.token", response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(900L, response.getExpiresIn());

        // Verify Set-Cookie header was written
        String cookieHeader = mockResponse.getHeader("Set-Cookie");
        assertNotNull(cookieHeader);
        assertTrue(cookieHeader.contains("refreshToken=sampleRefreshToken123"));
        assertTrue(cookieHeader.contains("HttpOnly"));
        assertTrue(cookieHeader.contains("SameSite=Lax"));
    }

    @Test
    void login_WithRememberMe_ShouldRequestPersistentRefreshToken() {
        String hashedPassword = passwordEncoder.encode("P@ssw0rd2026!");
        User verifiedUser = new User("elena.vance@datacenter.io", hashedPassword, "Elena Vance", "ROLE_USER");
        verifiedUser.setId(1L);
        verifiedUser.setVerified(true);

        when(userRepository.findByEmail("elena.vance@datacenter.io")).thenReturn(Optional.of(verifiedUser));
        when(jwtUtils.generateAccessToken(any(User.class))).thenReturn("mock.access.token");
        when(jwtUtils.getJwtExpirationMs()).thenReturn(900000L);

        RefreshToken persistentToken = new RefreshToken("persistentToken14d", verifiedUser, Instant.now().plusSeconds(1209600), true);
        when(refreshTokenService.createRefreshToken(any(User.class), eq(true))).thenReturn(persistentToken);
        when(refreshTokenService.getExpirationSeconds(eq(true))).thenReturn(1209600L);

        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        LoginRequest loginRequest = new LoginRequest("elena.vance@datacenter.io", "P@ssw0rd2026!", true);

        AuthResponse response = authService.login(loginRequest, mockRequest, mockResponse);

        assertTrue(response.isSuccess());
        verify(refreshTokenService).createRefreshToken(verifiedUser, true);
        String cookieHeader = mockResponse.getHeader("Set-Cookie");
        assertNotNull(cookieHeader);
        assertTrue(cookieHeader.contains("refreshToken=persistentToken14d"));
    }

    @Test
    void login_ShouldRequireOtp_WhenUserNotVerified() {
        String hashedPassword = passwordEncoder.encode("P@ssw0rd2026!");
        User unverifiedUser = new User("elena.vance@datacenter.io", hashedPassword, "Elena Vance", "Data Center Operations Manager");
        unverifiedUser.setVerified(false);

        when(userRepository.findByEmail("elena.vance@datacenter.io")).thenReturn(Optional.of(unverifiedUser));

        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        LoginRequest loginRequest = new LoginRequest("elena.vance@datacenter.io", "P@ssw0rd2026!");

        AuthResponse response = authService.login(loginRequest, mockRequest);

        assertTrue(response.isRequiresOtp());
        verify(emailService).sendOtpEmail(eq("elena.vance@datacenter.io"), anyString());
    }

    @Test
    void login_ShouldFail_WhenPasswordIncorrect() {
        String hashedPassword = passwordEncoder.encode("P@ssw0rd2026!");
        User verifiedUser = new User("elena.vance@datacenter.io", hashedPassword, "Elena Vance", "Role");
        verifiedUser.setVerified(true);

        when(userRepository.findByEmail("elena.vance@datacenter.io")).thenReturn(Optional.of(verifiedUser));

        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        LoginRequest loginRequest = new LoginRequest("elena.vance@datacenter.io", "WrongPassword!");

        assertThrows(BadCredentialsException.class, () -> authService.login(loginRequest, mockRequest));
    }

    @Test
    void refreshToken_ShouldRotateTokenAndSetNewCookie() {
        User user = new User("elena.vance@datacenter.io", "encoded", "Elena Vance", "ROLE_USER");
        RefreshToken rotatedToken = new RefreshToken("rotatedToken999", user, Instant.now().plusSeconds(1209600), true);

        when(refreshTokenService.rotateRefreshToken("incomingToken123")).thenReturn(rotatedToken);
        when(jwtUtils.generateAccessToken(user)).thenReturn("fresh.jwt.token");
        when(jwtUtils.getJwtExpirationMs()).thenReturn(900000L);
        when(refreshTokenService.getExpirationSeconds(true)).thenReturn(1209600L);

        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.setCookies(new Cookie("refreshToken", "incomingToken123"));
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        AuthResponse response = authService.refreshToken(mockRequest, mockResponse);

        assertTrue(response.isSuccess());
        assertEquals("fresh.jwt.token", response.getAccessToken());

        String setCookie = mockResponse.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("refreshToken=rotatedToken999"));
    }

    @Test
    void logout_ShouldRevokeRefreshTokenAndClearCookie() {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.setCookies(new Cookie("refreshToken", "tokenToRevoke"));
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();

        ApiResponse<Void> response = authService.logout(mockRequest, mockResponse);

        assertTrue(response.isSuccess());
        verify(refreshTokenService).revokeRefreshToken("tokenToRevoke");

        String setCookie = mockResponse.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("Max-Age=0"));
    }
}
