package com.gacs.backend.service;

import com.gacs.backend.dto.*;
import com.gacs.backend.model.OtpVerification;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.OtpVerificationRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.model.RefreshToken;
import com.gacs.backend.security.JwtUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final int OTP_EXPIRY_MINUTES = 10;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpVerificationRepository otpRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private Clock clock = Clock.systemUTC();

    @Value("${security.jwt.cookie-secure:false}")
    private boolean cookieSecure;

    @Value("${security.jwt.cookie-same-site:Lax}")
    private String cookieSameSite = "Lax";

    /** Recovery tokens are separate from registration OTPs and stored only as hashes. */
    @Transactional
    public ApiResponse<String> forgotPassword(ResendOtpRequest request) {
        String email = request.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        User user = userRepository.lockByEmail(email).orElse(null);
        String message = "If this email has a verified account, a recovery code will be sent.";
        LocalDateTime now = nowUtc();
        // Internal categories explain skipped sends without logging addresses or codes.
        // Keep the public response identical for every outcome.
        if (user == null) {
            logger.info("Password recovery email skipped: account not found");
            return ApiResponse.ok(message);
        }
        if (!user.isVerified()) {
            logger.info("Password recovery email skipped: account is not verified");
            return ApiResponse.ok(message);
        }
        LocalDateTime requestedAt = user.getPasswordResetRequestedAt();
        // Older local servers wrote Singapore wall time into these timezone-less
        // columns. A far-future value must not impose an eight-hour cooldown.
        // Allow a minute of clock skew between servers without bypassing throttling.
        if (requestedAt != null && !requestedAt.isAfter(now.plusSeconds(60)) &&
                requestedAt.isAfter(now.minusSeconds(60))) {
            logger.info("Password recovery email skipped: 60-second cooldown is active");
            return ApiResponse.ok(message);
        }
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // Do not log recovery tokens, including when Gmail API delivery fails.
        try {
            emailService.sendPasswordResetEmail(email, token);
            logger.info("Password recovery email accepted by Gmail API");
            user.setPasswordResetHash(passwordEncoder.encode(token));
            user.setPasswordResetExpiresAt(now.plusMinutes(10));
        } catch (RuntimeException ex) {
            logger.warn("Password recovery email delivery failed: {}",
                    ex instanceof IllegalStateException ? ex.getMessage() : ex.getClass().getSimpleName());
        }
        user.setPasswordResetRequestedAt(now);
        userRepository.save(user);
        return ApiResponse.ok(message);
    }

    @Transactional
    public ApiResponse<String> resetPassword(ResetPasswordRequest request) {
        User user = userRepository.lockByEmail(request.email().trim().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired recovery code. Request a new code."));
        LocalDateTime now = nowUtc();
        if (!user.isVerified() || user.getPasswordResetHash() == null ||
                user.getPasswordResetExpiresAt() == null ||
                !user.getPasswordResetExpiresAt().isAfter(now) ||
                // Do not extend legacy Singapore-local codes to eight hours in UTC.
                user.getPasswordResetExpiresAt().isAfter(now.plusMinutes(10)) ||
                !passwordEncoder.matches(request.token(), user.getPasswordResetHash())) {
            throw new IllegalArgumentException("Invalid or expired recovery code. Request a new code.");
        }
        com.gacs.backend.security.PasswordPolicy.validate(request.password());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordResetHash(null);
        user.setPasswordResetExpiresAt(null);
        userRepository.save(user);
        refreshTokenService.revokeAllUserTokens(user);
        return ApiResponse.ok("Password updated. Sign in with your new password.");
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * Registers a new user or refreshes registration for an unverified account.
     * Encrypts the password using BCrypt before storing in the database.
     * Generates a 6-digit OTP and sends it to the user's email via Gmail API.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        com.gacs.backend.security.PasswordPolicy.validate(request.getPassword());
        String email = request.getEmail().trim().toLowerCase();

        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User userToSave;
        if (existingUserOpt.isPresent()) {
            User existingUser = existingUserOpt.get();
            if (existingUser.isVerified()) {
                throw new IllegalArgumentException("An account with this email already exists. Please log in.");
            }
            // User exists but was not yet verified: update password (encrypted) and details
            existingUser.setPassword(passwordEncoder.encode(request.getPassword()));
            if (request.getFullName() != null && !request.getFullName().trim().isEmpty()) {
                existingUser.setFullName(request.getFullName().trim());
            }
            existingUser.setRole("ROLE_USER");
            userToSave = existingUser;
        } else {
            // New user registration - encrypt password with BCrypt, always assign ROLE_USER
            String encryptedPassword = passwordEncoder.encode(request.getPassword());
            User newUser = new User(email, encryptedPassword, request.getFullName(), "ROLE_USER");
            newUser.setVerified(false);
            userToSave = newUser;
        }

        // Generate and send OTP
        String otpCode = generateAndSaveOtp(email);
        emailService.sendOtpEmail(email, otpCode);
        userRepository.save(userToSave);

        return AuthResponse.otpRequired("Registration initiated. A 6-digit OTP has been sent to " + email + ". Please verify to complete registration.", email);
    }

    /**
     * Verifies the submitted OTP code and marks the user's account as verified.
     */
    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        String code = request.getOtp().trim();

        OtpVerification otp = otpRepository.findTopByEmailAndOtpCodeAndIsUsedFalseOrderByCreatedAtDesc(email, code)
                .orElseThrow(() -> new IllegalArgumentException("Invalid OTP verification code."));

        if (!otp.getExpiresAt().isAfter(nowUtc())) {
            throw new IllegalArgumentException("OTP verification code has expired. Please request a new OTP.");
        }

        // Mark OTP as used
        otp.setUsed(true);
        otpRepository.save(otp);

        // Mark user as verified
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User account not found."));
        user.setVerified(true);
        User savedUser = userRepository.save(user);

        return AuthResponse.success("Account successfully verified! You may now log in.", new UserDto(savedUser));
    }

    /**
     * Resends a new OTP to the specified email.
     */
    @Transactional
    public ApiResponse<String> resendOtp(ResendOtpRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("No account found for this email."));

        if (user.isVerified()) {
            return ApiResponse.ok("Account is already verified. Please proceed to login.");
        }

        String otpCode = generateAndSaveOtp(email);
        emailService.sendOtpEmail(email, otpCode);

        return ApiResponse.ok("A new OTP code has been sent to " + email + ".");
    }

    /**
     * Authenticates the user with BCrypt password verification and checks OTP status.
     * Issues a short-lived JWT Access Token (15 min) and a persistent Refresh Token in an HttpOnly cookie
     * (14 days if rememberMe is enabled, 24 hours otherwise).
     */
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        return login(request, httpRequest, null);
    }

    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password."));

        // Verify password against BCrypt hash
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid email or password.");
        }

        // After the password check, so only the owner learns the account is disabled;
        // before the OTP check, so a banned account gets no OTP emails.
        if (user.isBanned()) {
            throw new BadCredentialsException("This account is disabled. Contact an administrator.");
        }

        // Check if account has completed OTP verification
        if (!user.isVerified()) {
            String otpCode = generateAndSaveOtp(email);
            emailService.sendOtpEmail(email, otpCode);
            return AuthResponse.otpRequired("Account is not yet verified. A fresh OTP has been sent to your email.", email);
        }

        // Set authenticated user in Spring Security Context
        String rawRole = user.getRole();
        String authorityName;
        if (rawRole != null && rawRole.startsWith("ROLE_")) {
            authorityName = rawRole;
        } else if (rawRole != null && !rawRole.trim().isEmpty()) {
            authorityName = "ROLE_" + rawRole.toUpperCase().replace(" ", "_");
        } else {
            authorityName = "ROLE_USER";
        }

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                user.getEmail(),
                null,
                Collections.singletonList(new SimpleGrantedAuthority(authorityName))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authToken);
        SecurityContextHolder.setContext(context);

        if (httpRequest != null) {
            HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            }
        }

        // Generate JWT Access Token (15 min)
        String accessToken = jwtUtils.generateAccessToken(user);

        // Generate and persist Refresh Token (Remember-Me persistence)
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user, request.isRememberMe());
        long refreshExpirySeconds = refreshTokenService.getExpirationSeconds(refreshToken.isRememberMe());

        // Set HttpOnly, Secure, SameSite=Lax cookie for the Refresh Token
        if (httpResponse != null) {
            addRefreshTokenCookie(httpResponse, refreshToken.getToken(), refreshExpirySeconds);
        }

        AuthResponse response = AuthResponse.successWithToken(
                "Login successful.",
                new UserDto(user),
                accessToken,
                jwtUtils.getJwtExpirationMs() / 1000,
                refreshExpirySeconds
        );
        response.setRefreshToken(refreshToken.getToken());
        return response;
    }

    /**
     * Rotates the refresh token and returns a fresh JWT access token.
     */
    public AuthResponse refreshToken(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String tokenStr = extractRefreshTokenFromCookie(httpRequest);
        if (tokenStr == null || tokenStr.trim().isEmpty()) {
            throw new BadCredentialsException("Refresh token cookie is missing.");
        }

        RefreshToken rotatedToken = refreshTokenService.rotateRefreshToken(tokenStr);
        User user = rotatedToken.getUser();
        long refreshExpirySeconds = refreshTokenService.getExpirationSeconds(rotatedToken.isRememberMe());

        // Issue fresh access token
        String newAccessToken = jwtUtils.generateAccessToken(user);

        // Update HttpOnly cookie with the new rotated refresh token
        if (httpResponse != null) {
            addRefreshTokenCookie(httpResponse, rotatedToken.getToken(), refreshExpirySeconds);
        }

        AuthResponse response = AuthResponse.successWithToken(
                "Token refreshed successfully.",
                new UserDto(user),
                newAccessToken,
                jwtUtils.getJwtExpirationMs() / 1000,
                refreshExpirySeconds
        );
        response.setRefreshToken(rotatedToken.getToken());
        return response;
    }

    /**
     * Logs out the user by revoking the refresh token, clearing cookie, and clearing SecurityContext.
     */
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        if (httpRequest != null) {
            String tokenStr = extractRefreshTokenFromCookie(httpRequest);
            if (tokenStr != null) {
                refreshTokenService.revokeRefreshToken(tokenStr);
            }
            HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }

        if (httpResponse != null) {
            clearRefreshTokenCookie(httpResponse);
        }

        SecurityContextHolder.clearContext();
        return ApiResponse.ok("Logged out successfully.");
    }


    /**
     * Retrieves the current user's profile from the authenticated email.
     */
    public UserDto getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User account not found."));
        return new UserDto(user);
    }

    /**
     * Updates the current user's full name.
     */
    @Transactional
    public UserDto updateFullName(String email, String fullName) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User account not found."));
        user.setFullName(fullName.trim());
        return new UserDto(userRepository.save(user));
    }

    private void addRefreshTokenCookie(HttpServletResponse response, String refreshToken, long maxAgeSeconds) {
        boolean secure = cookieSecure || "None".equalsIgnoreCase(cookieSameSite);
        ResponseCookie cookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(maxAgeSeconds)
                .sameSite(cookieSameSite)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        boolean secure = cookieSecure || "None".equalsIgnoreCase(cookieSameSite);
        ResponseCookie cookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(0)
                .sameSite(cookieSameSite)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String extractRefreshTokenFromCookie(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("refreshToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        String headerToken = request.getHeader("X-Refresh-Token");
        if (headerToken != null && !headerToken.trim().isEmpty()) {
            return headerToken.trim();
        }
        return null;
    }

    /**
     * Helper to invalidate existing unused OTPs and generate a fresh 6-digit numeric OTP.
     */
    private String generateAndSaveOtp(String email) {
        // Invalidate prior unused OTPs
        List<OtpVerification> existingOtps = otpRepository.findAllByEmailAndIsUsedFalse(email);
        for (OtpVerification prior : existingOtps) {
            prior.setUsed(true);
        }
        otpRepository.saveAll(existingOtps);

        // Generate 6-digit number string
        int randomPin = 100_000 + secureRandom.nextInt(900_000);
        String otpCode = String.valueOf(randomPin);

        OtpVerification otp = new OtpVerification(
                email,
                otpCode,
                nowUtc().plusMinutes(OTP_EXPIRY_MINUTES)
        );
        otpRepository.save(otp);
        return otpCode;
    }
}
